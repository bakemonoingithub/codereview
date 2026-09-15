package com.codereview.service;

import com.codereview.analyzer.AnalysisContext;
import com.codereview.analyzer.AnalyzeOutcome;
import com.codereview.analyzer.Analyzer;
import com.codereview.common.ReviewableFiles;
import com.codereview.entity.Project;
import com.codereview.entity.ReviewRecord;
import com.codereview.git.GitRepoRef;
import com.codereview.mapper.ProjectMapper;
import com.codereview.mapper.ReviewRecordMapper;
import com.codereview.review.ReviewStatus;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 审查编排（M3）：按策略分析器类型分发到对应 Analyzer，负责记录生命周期（执行中/成功/失败）。
 *
 * <p><b>可审查过滤也在这层</b>：四个分析器共享这里的判定，不必各自实现一遍
 * （各自的实现方式是"来者不拒"——llm-review/diff-review 会把内容整段送模型，
 * coupling/design-pattern 靠解析失败静默丢弃）。放在编排层意味着将来新增分析器**自动受益**。
 */
@Slf4j
@Component
public class ReviewExecutor {

    /** error_message 的列宽（V6 迁移），写入侧必须自己负责上限。 */
    static final int ERROR_MESSAGE_MAX = 1000;
    /** 结果体积告警阈值：现实载荷是几十 KB～几百 KB，超过它说明有异常大的记录。 */
    static final int RESULT_WARN_BYTES = 1024 * 1024;

    private final ReviewRecordMapper reviewRecordMapper;
    private final ProjectMapper projectMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<Integer, Analyzer> analyzers;

    public ReviewExecutor(ReviewRecordMapper reviewRecordMapper, ProjectMapper projectMapper,
                          List<Analyzer> analyzers) {
        this.reviewRecordMapper = reviewRecordMapper;
        this.projectMapper = projectMapper;
        this.analyzers = analyzers.stream().collect(Collectors.toMap(Analyzer::type, Function.identity()));
    }

    @Async("reviewTaskExecutor")
    public void execute(Long recordId) {
        ReviewRecord record = reviewRecordMapper.selectById(recordId);
        if (record == null) {
            return;
        }
        markRunning(record);
        try {
            Scope scope = resolveScope(record);
            if (scope.allFilteredOut()) {
                markFailedWithReason(record, emptyScopeReason(scope.raw().size()));
                return;
            }
            AnalyzeOutcome outcome = analyzer(record).analyze(buildContext(record, scope.effective()));
            persist(record, outcome, scope.skipped());
        } catch (Exception e) {
            log.error("审查执行失败 recordId={}", recordId, e);
            // 原因要落到记录上：否则界面只有一条"失败"，无从判断是网关、凭据还是范围问题
            markFailedWithReason(record, "审查执行失败：" + rootMessage(e));
        }
    }

    @Async("reviewTaskExecutor")
    public void retry(Long recordId) {
        ReviewRecord record = reviewRecordMapper.selectById(recordId);
        if (record == null) {
            return;
        }
        try {
            JsonNode oldResult = record.getResultJson() == null || record.getResultJson().isBlank()
                    ? null : objectMapper.readTree(record.getResultJson());
            markRunning(record);
            Scope scope = resolveScope(record);
            if (scope.allFilteredOut()) {
                markFailedWithReason(record, emptyScopeReason(scope.raw().size()));
                return;
            }
            AnalyzeOutcome outcome = analyzer(record).retry(buildContext(record, scope.effective()), oldResult);
            persist(record, outcome, scope.skipped());
        } catch (Exception e) {
            log.error("重审失败 recordId={}", recordId, e);
            markFailedWithReason(record, "重审失败：" + rootMessage(e));
        }
    }

    private Analyzer analyzer(ReviewRecord record) throws Exception {
        JsonNode snap = objectMapper.readTree(record.getStrategySnapshotJson());
        int type = snap.path("analyzerType").asInt(1);
        Analyzer a = analyzers.get(type);
        if (a == null) {
            throw new IllegalStateException("不支持的分析器类型: " + type);
        }
        return a;
    }

    /**
     * 用户勾选范围 → 实际执行范围。
     *
     * <p>{@code raw} 为空是**合法**的：diff 审查允许空范围，含义是"审全部变更文件"
     * （见 {@code ReviewService#trigger}），不能当成"没有可审查文件"拒绝。
     */
    private Scope resolveScope(ReviewRecord record) throws Exception {
        List<String> raw = objectMapper.readValue(
                record.getScopeJson() == null || record.getScopeJson().isBlank() ? "[]" : record.getScopeJson(),
                new TypeReference<List<String>>() {
                });
        return new Scope(raw, ReviewableFiles.reviewableOnly(raw));
    }

    private AnalysisContext buildContext(ReviewRecord record, List<String> scope) throws Exception {
        Project project = projectMapper.selectById(record.getProjectId());
        GitRepoRef ref = GitRepoRef.parse(project.getGiteaUrl());
        JsonNode snap = objectMapper.readTree(record.getStrategySnapshotJson());
        JsonNode model = snap.path("model");
        return new AnalysisContext(record, project, ref, scope,
                model.path("baseUrl").asText(),
                model.path("apiKey").asText(),
                model.path("modelName").asText(),
                snap.path("params"),
                snap.path("customPrompt").asText(null),
                snap.path("mergeFiles").asBoolean(false),
                pct -> {
                    record.setProgress(pct);
                    reviewRecordMapper.updateById(record);
                });
    }

    /**
     * 落库结果。
     *
     * <p>被过滤掉的文件不产生单元，所以记录里会出现"范围 N 个文件、结果只有 M 个单元"的差。
     * 这里**把差额写进 summary**：弹窗看过就没了，但记录是长期留存的资产，
     * 不写清楚的话事后会被当成 bug 排查。
     *
     * <p><b>落库失败必须变成"失败 + 原因"，不能把记录卡在"执行中"</b>：
     * 早先的写法是异常冒泡给 {@link #execute} 的 catch 再调 {@code markFailed}，
     * 而那次失败写用的实体**还带着刚刚落库失败的超大 result_json**，于是第二次同样失败、
     * 异常逃出 {@code @Async} 方法（没有 UncaughtExceptionHandler），
     * 记录永久停在 status=1/progress=0，接口层面零错误可见。
     */
    private void persist(ReviewRecord record, AnalyzeOutcome outcome, int skipped) {
        JsonNode result = outcome.result();
        if (skipped > 0 && result instanceof ObjectNode obj) {
            String summary = obj.path("summary").asText("");
            obj.put("summary", summary.isBlank()
                    ? "已跳过 " + skipped + " 个非可审查文件"
                    : summary + "；已跳过 " + skipped + " 个非可审查文件");
        }
        String json = result.toString();
        warnIfOversized(record.getId(), json);
        record.setResultJson(json);
        record.setStatus(outcome.status());
        record.setProgress(100);
        record.setFinishedAt(LocalDateTime.now());
        // 成功/部分成功不留旧的失败原因（重审成功的场景）
        record.setErrorMessage(null);
        try {
            reviewRecordMapper.updateById(record);
        } catch (Exception e) {
            log.error("审查结果落库失败 recordId={} resultBytes={}", record.getId(),
                    json.getBytes(StandardCharsets.UTF_8).length, e);
            markFailedWithReason(record, "结果落库失败：" + rootMessage(e));
        }
    }

    /**
     * 结果异常大时留一条日志。
     *
     * <p>阈值远高于现实载荷（几十 KB～几百 KB），用途是**验收前发现病理级记录**，
     * 而不是常规体检 —— 贴近旧的 64KB 上限只会让日志噪音掩盖真正的信号。
     */
    private static void warnIfOversized(Long recordId, String json) {
        int bytes = json.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > RESULT_WARN_BYTES) {
            log.warn("审查结果体积异常 recordId={} bytes={} threshold={}", recordId, bytes, RESULT_WARN_BYTES);
        }
    }

    /** 取最内层原因，避免把整条 SQL 都糊到界面上。 */
    private static String rootMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }

    /** 把记录落到失败态并写明原因；失败原因统一走这里，不再往 result_json 里塞。 */
    private void markFailedWithReason(ReviewRecord record, String reason) {
        record.setResultJson(null);
        record.setErrorMessage(bounded(reason));
        record.setStatus(ReviewStatus.FAILED);
        record.setProgress(100);
        record.setFinishedAt(LocalDateTime.now());
        try {
            reviewRecordMapper.updateById(record);
        } catch (Exception e) {
            // 连失败态都写不进去（例如库不可用）时，至少别让异常静默消失
            log.error("写失败态也失败 recordId={}", record.getId(), e);
        }
    }

    /** 截断到列宽：error_message 是有界列，写入侧必须自己负责上限。 */
    static String bounded(String reason) {
        if (reason == null) {
            return null;
        }
        return reason.length() <= ERROR_MESSAGE_MAX ? reason : reason.substring(0, ERROR_MESSAGE_MAX - 1) + "…";
    }

    private void markRunning(ReviewRecord record) {
        record.setStatus(ReviewStatus.RUNNING);
        if (record.getStartedAt() == null) {
            record.setStartedAt(LocalDateTime.now());
        }
        record.setProgress(0);
        // 重审时清掉上一次的失败原因（实体策略是"总是写这一列"，故这里必须显式置空）
        record.setErrorMessage(null);
        reviewRecordMapper.updateById(record);
    }

    private static String emptyScopeReason(int total) {
        return "范围内没有可审查的文件（" + total + " 个文件均不在可审查名单内）";
    }

    /** 勾选范围与过滤后的执行范围。 */
    private record Scope(List<String> raw, List<String> effective) {

        int skipped() {
            return raw.size() - effective.size();
        }

        /** 用户勾了文件、但一个都不在名单内 —— 这才是"没有可审查的文件"。 */
        boolean allFilteredOut() {
            return !raw.isEmpty() && effective.isEmpty();
        }
    }
}
