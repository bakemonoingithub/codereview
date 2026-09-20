package com.codereview.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.codereview.common.AnalyzerTypes;
import com.codereview.common.BusinessException;
import com.codereview.common.PageLimits;
import com.codereview.common.ResultCode;
import com.codereview.common.ReviewableFiles;
import com.codereview.dto.ReviewRecordResp;
import com.codereview.dto.ReviewRecordRow;
import com.codereview.dto.ReviewTriggerReq;
import com.codereview.entity.ModelConfig;
import com.codereview.entity.Project;
import com.codereview.entity.Prompt;
import com.codereview.entity.PromptVersion;
import com.codereview.entity.ReviewRecord;
import com.codereview.entity.ReviewStrategy;
import com.codereview.git.GitHostClientRegistry;
import com.codereview.git.GitRepoRef;
import com.codereview.mapper.ModelConfigMapper;
import com.codereview.mapper.ProjectMapper;
import com.codereview.mapper.PromptMapper;
import com.codereview.mapper.PromptVersionMapper;
import com.codereview.mapper.ReviewRecordMapper;
import com.codereview.mapper.ReviewStrategyMapper;
import com.codereview.review.ReviewStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ReviewService {

    private final ReviewRecordMapper reviewRecordMapper;
    private final ProjectMapper projectMapper;
    private final ReviewStrategyMapper strategyMapper;
    private final ModelConfigMapper modelConfigMapper;
    private final GitHostClientRegistry gitHostClients;
    private final ReviewExecutor reviewExecutor;
    private final PromptMapper promptMapper;
    private final PromptVersionMapper promptVersionMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ReviewService(ReviewRecordMapper reviewRecordMapper, ProjectMapper projectMapper,
                         ReviewStrategyMapper strategyMapper, ModelConfigMapper modelConfigMapper,
                         GitHostClientRegistry gitHostClients, ReviewExecutor reviewExecutor,
                         PromptMapper promptMapper, PromptVersionMapper promptVersionMapper) {
        this.reviewRecordMapper = reviewRecordMapper;
        this.projectMapper = projectMapper;
        this.strategyMapper = strategyMapper;
        this.modelConfigMapper = modelConfigMapper;
        this.gitHostClients = gitHostClients;
        this.reviewExecutor = reviewExecutor;
        this.promptMapper = promptMapper;
        this.promptVersionMapper = promptVersionMapper;
    }

    public ReviewRecord trigger(Long projectId, ReviewTriggerReq req) {
        Project p = projectMapper.selectById(projectId);
        if (p == null) {
            throw new BusinessException(ResultCode.PROJECT_NOT_FOUND);
        }
        if (req.strategyId() == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "请选择审查策略");
        }
        ReviewStrategy strategy = strategyMapper.selectById(req.strategyId());
        if (strategy == null) {
            throw new BusinessException(ResultCode.STRATEGY_NOT_FOUND);
        }
        int analyzerType = strategy.getAnalyzerType() == null ? 0 : strategy.getAnalyzerType();
        if (!AnalyzerTypes.isValid(analyzerType)) {
            throw new BusinessException(ResultCode.ANALYZER_TYPE_UNSUPPORTED);
        }
        boolean diffReview = AnalyzerTypes.requiresCommitSha(analyzerType);
        // diff 审查的范围由提交的变更文件决定，允许为空（为空即审全部变更文件）
        if (analyzerType != AnalyzerTypes.API_REVIEW && !diffReview && (req.scope() == null || req.scope().isEmpty())) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "请选择审查范围");
        }
        // 勾了文件、但一个都不在可审查名单内：在入口就拒绝，不产生一条注定失败的记录。
        // （前端也会拦；这里是绕过前端直接调 API 时的兜底。）
        if (analyzerType != AnalyzerTypes.API_REVIEW
                && req.scope() != null && !req.scope().isEmpty()
                && ReviewableFiles.reviewableOnly(req.scope()).isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(),
                    "所选文件中没有可审查的文件（共 " + req.scope().size() + " 个均不在可审查名单内）");
        }
        ModelConfig model = AnalyzerTypes.requiresModel(analyzerType) ? resolveModelConfig(strategy) : null;
        String commitSha;
        if (analyzerType == AnalyzerTypes.API_REVIEW) {
            commitSha = null;
        } else if (diffReview) {
            // 必须锚定到具体提交：不接受缺省，否则会静默审到分支最新代码
            if (req.commitSha() == null || req.commitSha().isBlank()) {
                throw new BusinessException(ResultCode.PARAM_ERROR.getCode(),
                        "diff 审查必须指定提交（commitSha）");
            }
            commitSha = req.commitSha().trim();
        } else {
            commitSha = resolveCommitSha(p, req.branch());
        }

        ReviewRecord record = new ReviewRecord();
        record.setProjectId(projectId);
        record.setStrategyId(strategy.getId());
        record.setStrategySnapshotJson(buildSnapshot(strategy, model, Boolean.TRUE.equals(req.mergeFiles())));
        record.setBranch(req.branch());
        record.setCommitSha(commitSha);
        try {
            record.setScopeJson(objectMapper.writeValueAsString(req.scope()));
        } catch (Exception e) {
            throw new IllegalStateException("序列化 scope 失败", e);
        }
        record.setStatus(0);
        record.setProgress(0);
        reviewRecordMapper.insert(record);
        reviewExecutor.execute(record.getId());
        return record;
    }

    public void retry(Long reviewId) {
        ReviewRecord r = getOrThrow(reviewId);
        // 只有失败(3)与部分成功(4)可重审 —— 与前端 canRetry / 置灰按钮同口径。
        // 原先的判据是 `status < 2`（未结束才拒），等于**允许对成功记录重审**：
        // 前端按钮是灰的，这条路径只能被直接调 API 触发，而重审会覆盖已确认的结果资产
        // （指标 5 的口径正是这些结果），成功率极低却代价明确。前后端口径不一致本身也是坑。
        if (!isRetryable(r.getStatus())) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(),
                    "仅失败或部分成功的审查可重审（当前状态：" + statusText(r.getStatus()) + "）");
        }
        // 同步置为执行中，避免前端轮询竞态；成功单元结果仍保留在 result_json 中，仅失败部分重跑
        r.setStatus(ReviewStatus.RUNNING);
        r.setProgress(0);
        reviewRecordMapper.updateById(r);
        reviewExecutor.retry(reviewId);
    }

    /** 与前端 {@code canRetry} 一致：仅失败/部分成功可重审。 */
    static boolean isRetryable(Integer status) {
        return status != null && (status == ReviewStatus.FAILED || status == ReviewStatus.PARTIAL);
    }

    private static String statusText(Integer status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case ReviewStatus.QUEUED -> "排队中";
            case ReviewStatus.RUNNING -> "执行中";
            case ReviewStatus.SUCCESS -> "成功";
            case ReviewStatus.FAILED -> "失败";
            case ReviewStatus.PARTIAL -> "部分成功";
            default -> String.valueOf(status);
        };
    }

    public ReviewRecordResp detail(Long reviewId) {
        ReviewRecord r = getOrThrow(reviewId);
        return new ReviewRecordResp(r.getId(), r.getProjectId(), r.getStrategyId(), r.getBranch(), r.getCommitSha(),
                r.getScopeJson(), r.getStatus(), r.getProgress(), r.getResultJson(), r.getErrorMessage(),
                r.getStartedAt(), r.getFinishedAt(), r.getCreatedAt());
    }

    public Page<ReviewRecordRow> list(Long projectId, long pageNum, long pageSize) {
        return list(projectId, pageNum, pageSize, null);
    }

    /**
     * 审查记录列表（分页）。
     *
     * 三个刻意的选择：
     * <ol>
     *   <li><b>列投影</b>：只 select 列表真正用到的列，绝不带出 {@code result_json} /
     *       {@code scope_json} / {@code strategy_snapshot_json}。前者单条可达 MB 级（内含每个单元的
     *       LLM 原文），后者**内含模型明文 apiKey** —— 分页会让这个泄漏被反复触发。</li>
     *   <li><b>pageSize 截断</b>：见 {@link PageLimits}，拦截器没有 maxLimit，不兜住就能被要求查十万行。</li>
     *   <li><b>策略名批量补齐</b>：列表要显示策略名而记录只存 id，一次 {@code selectBatchIds} 补齐，
     *       避免逐行查询（N+1）。</li>
     * </ol>
     *
     * @param statusMin 可选状态下限（含）：只有 {@code status >= statusMin} 的记录会被返回。
     *                  报告生成页只要已完成记录（2）—— 未完成记录还没有 resultJson，进了报告也没内容，
     *                  却会占满整页甚至造成空页，且让 total 与"可选记录数"对不上。
     */
    public Page<ReviewRecordRow> list(Long projectId, long pageNum, long pageSize, Integer statusMin) {
        LambdaQueryWrapper<ReviewRecord> wrapper = new LambdaQueryWrapper<ReviewRecord>()
                .select(ReviewRecord::getId, ReviewRecord::getProjectId, ReviewRecord::getStrategyId,
                        ReviewRecord::getBranch, ReviewRecord::getCommitSha, ReviewRecord::getStatus,
                        ReviewRecord::getProgress, ReviewRecord::getStartedAt, ReviewRecord::getFinishedAt,
                        ReviewRecord::getCreatedAt)
                .eq(ReviewRecord::getProjectId, projectId)
                .ge(statusMin != null, ReviewRecord::getStatus, statusMin)
                .orderByDesc(ReviewRecord::getCreatedAt);

        Page<ReviewRecord> page = reviewRecordMapper.selectPage(
                PageLimits.page(pageNum, pageSize), wrapper);

        Map<Long, String> strategyNames = loadStrategyNames(page.getRecords());
        Page<ReviewRecordRow> rows = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        rows.setRecords(page.getRecords().stream()
                .map(r -> new ReviewRecordRow(r.getId(), r.getProjectId(), r.getStrategyId(),
                        strategyNames.get(r.getStrategyId()), r.getBranch(), r.getCommitSha(),
                        r.getStatus(), r.getProgress(), r.getStartedAt(), r.getFinishedAt(), r.getCreatedAt()))
                .toList());
        return rows;
    }

    private Map<Long, String> loadStrategyNames(List<ReviewRecord> records) {
        List<Long> ids = records.stream()
                .map(ReviewRecord::getStrategyId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return strategyMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(ReviewStrategy::getId, s -> s.getName() == null ? "" : s.getName()));
    }

    private ReviewRecord getOrThrow(Long reviewId) {
        ReviewRecord r = reviewRecordMapper.selectById(reviewId);
        if (r == null) {
            throw new BusinessException(ResultCode.REVIEW_NOT_FOUND);
        }
        return r;
    }

    private ModelConfig resolveModelConfig(ReviewStrategy strategy) {
        try {
            JsonNode params = objectMapper.readTree(strategy.getParamsJson());
            long modelId = Long.parseLong(params.path("modelConfigId").asText());
            ModelConfig m = modelConfigMapper.selectById(modelId);
            if (m == null) {
                throw new BusinessException(ResultCode.MODEL_NOT_FOUND);
            }
            return m;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "策略参数解析失败: " + e.getMessage());
        }
    }

    private String resolveCommitSha(Project p, String branch) {
        try {
            GitRepoRef ref = GitRepoRef.parse(p.getGiteaUrl());
            return gitHostClients.forRepo(ref)
                    .headCommitSha(p.getCredential(), p.getCredentialType(), ref, branch);
        } catch (Exception e) {
            log.warn("解析 HEAD commit sha 失败，降级留空: {}", e.getMessage());
            return null;
        }
    }

    private String buildSnapshot(ReviewStrategy strategy, ModelConfig model, boolean mergeFiles) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("strategyId", strategy.getId().toString());
        root.put("strategyName", strategy.getName());
        root.put("analyzerType", strategy.getAnalyzerType());
        root.put("mergeFiles", mergeFiles);
        if (model != null) {
            ObjectNode m = root.putObject("model");
            m.put("baseUrl", model.getBaseUrl());
            m.put("apiKey", model.getToken());
            m.put("modelName", model.getModelName());
        }
        try {
            JsonNode params = objectMapper.readTree(
                    strategy.getParamsJson() == null || strategy.getParamsJson().isBlank() ? "{}" : strategy.getParamsJson());
            root.set("params", params);
            String prompt = resolvePromptContent(params);
            if (prompt != null) {
                root.put("customPrompt", prompt);
            }
        } catch (Exception e) {
            throw new IllegalStateException("序列化策略快照失败", e);
        }
        return root.toString();
    }

    /** 从策略参数解析提示词 → 当前版本内容（关注点规则），注入快照供分析器使用。 */
    private String resolvePromptContent(JsonNode params) {
        String pid = params.path("promptId").asText(null);
        if (pid == null || pid.isBlank()) {
            pid = params.path("promptVersionId").asText(null);
        }
        if (pid == null || pid.isBlank()) {
            return null;
        }
        try {
            Prompt p = promptMapper.selectById(Long.parseLong(pid));
            if (p == null || p.getCurrentVersionId() == null) {
                return null;
            }
            PromptVersion v = promptVersionMapper.selectById(p.getCurrentVersionId());
            return v == null ? null : v.getContent();
        } catch (Exception e) {
            return null;
        }
    }
}
