package com.codereview.service;

import com.codereview.chunk.Chunker;
import com.codereview.chunk.ReviewUnit;
import com.codereview.config.ReviewProperties;
import com.codereview.entity.Project;
import com.codereview.entity.ReviewRecord;
import com.codereview.git.GitHostClient;
import com.codereview.git.GitRepoRef;
import com.codereview.llm.LlmClient;
import com.codereview.mapper.ProjectMapper;
import com.codereview.mapper.ReviewRecordMapper;
import com.codereview.review.ReviewStatus;
import com.codereview.review.RetryPolicy;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;

/**
 * 审查编排（M2）：制备单元（拉取 + 分块）→ 并行限流执行（单元级重试）→ 聚合落库。
 * result_json = {units:[{path, unit:{kind,name,lines}, status, issues[], summary, error}], summary}
 */
@Slf4j
@Component
public class ReviewExecutor {

    private static final String SYSTEM_PROMPT =
            "你是资深代码审查助手，只输出合法 JSON，不要输出任何其他文字。";
    private static final String USER_TEMPLATE =
            "请审查下面的 Java 代码单元，找出问题（命名规范、代码缺陷、业务规则、设计问题），"
                    + "并以 JSON 返回，格式：{\"issues\":[{\"severity\":\"MAJOR|MINOR|INFO\","
                    + "\"category\":\"...\",\"line\":行号,\"title\":\"...\",\"description\":\"...\","
                    + "\"suggestion\":\"...\"}],\"summary\":\"一句话概述\"}。\n\n单元信息：%s\n代码：\n%s";

    private final ReviewRecordMapper reviewRecordMapper;
    private final ProjectMapper projectMapper;
    private final GitHostClient gitHostClient;
    private final LlmClient llmClient;
    private final ThreadPoolTaskExecutor unitExecutor;
    private final ReviewProperties props;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ReviewExecutor(ReviewRecordMapper reviewRecordMapper, ProjectMapper projectMapper,
                          GitHostClient gitHostClient, LlmClient llmClient,
                          ThreadPoolTaskExecutor unitExecutor, ReviewProperties props) {
        this.reviewRecordMapper = reviewRecordMapper;
        this.projectMapper = projectMapper;
        this.gitHostClient = gitHostClient;
        this.llmClient = llmClient;
        this.unitExecutor = unitExecutor;
        this.props = props;
    }

    @Async
    public void execute(Long recordId) {
        ReviewRecord record = reviewRecordMapper.selectById(recordId);
        if (record == null) {
            return;
        }
        markRunning(record);
        try {
            Project project = projectMapper.selectById(record.getProjectId());
            GitRepoRef ref = GitRepoRef.parse(project.getGiteaUrl());
            Snapshot snap = parseSnapshot(record.getStrategySnapshotJson());
            List<String> scope = parseScope(record.getScopeJson());
            ArrayNode units = runPaths(record, project, ref, snap, scope);
            finish(record, units);
        } catch (Exception e) {
            log.error("审查执行失败 recordId={}", recordId, e);
            markFailed(record);
        }
    }

    @Async
    public void retry(Long recordId) {
        ReviewRecord record = reviewRecordMapper.selectById(recordId);
        if (record == null) {
            return;
        }
        try {
            List<String> failedPaths = failedPathsToRun(record);
            if (failedPaths.isEmpty()) {
                return;
            }
            markRunning(record);
            Project project = projectMapper.selectById(record.getProjectId());
            GitRepoRef ref = GitRepoRef.parse(project.getGiteaUrl());
            Snapshot snap = parseSnapshot(record.getStrategySnapshotJson());
            ArrayNode newUnits = runPaths(record, project, ref, snap, failedPaths);
            // 合并：剔除旧结果中失败路径的单元，追加新单元
            Set<String> failedSet = new LinkedHashSet<>(failedPaths);
            ArrayNode merged = objectMapper.createArrayNode();
            JsonNode oldRoot = objectMapper.readTree(record.getResultJson());
            for (JsonNode u : oldRoot.path("units")) {
                if (!failedSet.contains(u.path("path").asText())) {
                    merged.add(u);
                }
            }
            newUnits.forEach(merged::add);
            finish(record, merged);
        } catch (Exception e) {
            log.error("重审失败 recordId={}", recordId, e);
            markFailed(record);
        }
    }

    /** 拉取 + 分块制备单元，再并行执行（限流 + 单元级重试），逐单元更新进度。 */
    private ArrayNode runPaths(ReviewRecord record, Project project, GitRepoRef ref, Snapshot snap, List<String> paths) {
        List<UnitTask> tasks = new ArrayList<>();
        for (String path : paths) {
            try {
                String code = gitHostClient.rawFile(project.getCredential(), ref.owner(), ref.repo(), record.getBranch(), path);
                for (ReviewUnit u : Chunker.chunk(path, code, props.getChunkMaxChars())) {
                    tasks.add(UnitTask.of(u));
                }
            } catch (Exception e) {
                tasks.add(UnitTask.fetchFailed(path, e.getMessage()));
            }
        }
        ArrayNode results = objectMapper.createArrayNode();
        if (tasks.isEmpty()) {
            return results;
        }
        CompletionService<ObjectNode> cs = new ExecutorCompletionService<>(unitExecutor);
        int submitted = 0;
        for (UnitTask task : tasks) {
            cs.submit(() -> executeUnit(snap, task));
            submitted++;
        }
        int done = 0;
        while (done < submitted) {
            try {
                Future<ObjectNode> f = cs.take();
                results.add(f.get());
            } catch (Exception e) {
                results.add(failedUnit(UnitTask.fallback(), "执行异常: " + e.getMessage()));
            }
            done++;
            record.setProgress(done * 100 / submitted);
            reviewRecordMapper.updateById(record);
        }
        return results;
    }

    /** 执行单个单元：带指数退避重试（只重试可重试错误）。 */
    private ObjectNode executeUnit(Snapshot snap, UnitTask task) {
        if (task.fetchError() != null) {
            return failedUnit(task, "拉取失败: " + task.fetchError());
        }
        Throwable last = null;
        for (int attempt = 0; attempt <= props.getRetryMax(); attempt++) {
            if (attempt > 0) {
                try {
                    Thread.sleep(RetryPolicy.backoffMillis(attempt - 1));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            try {
                String content = llmClient.chatJson(snap.baseUrl(), snap.apiKey(), snap.modelName(),
                        SYSTEM_PROMPT, buildUserPrompt(task));
                JsonNode result = objectMapper.readTree(content);
                return successUnit(task, result);
            } catch (Throwable e) {
                last = e;
                if (!RetryPolicy.isRetryable(e)) {
                    break; // 确定性错误不重试
                }
            }
        }
        return failedUnit(task, last == null ? "未知错误" : last.getMessage());
    }

    private String buildUserPrompt(UnitTask task) {
        String header = String.format("{文件路径:%s, 类型:%s, 名称:%s, 行范围:%d-%d}",
                task.path(), task.kind(), task.name(), task.startLine(), task.endLine());
        return String.format(USER_TEMPLATE, header, task.code());
    }

    private ObjectNode successUnit(UnitTask task, JsonNode llmResult) {
        ObjectNode o = baseUnit(task);
        o.put("status", "success");
        o.set("issues", llmResult.path("issues"));
        o.put("summary", llmResult.path("summary").asText(""));
        return o;
    }

    private ObjectNode failedUnit(UnitTask task, String error) {
        ObjectNode o = baseUnit(task);
        o.put("status", "failed");
        o.put("error", error);
        return o;
    }

    private ObjectNode baseUnit(UnitTask task) {
        ObjectNode o = objectMapper.createObjectNode();
        o.put("path", task.path());
        ObjectNode unit = o.putObject("unit");
        unit.put("kind", task.kind());
        unit.put("name", task.name());
        unit.put("lines", task.startLine() + "-" + task.endLine());
        return o;
    }

    private void finish(ReviewRecord record, ArrayNode units) {
        int total = units.size();
        int success = 0;
        int failed = 0;
        for (JsonNode u : units) {
            if ("success".equals(u.path("status").asText())) {
                success++;
            } else {
                failed++;
            }
        }
        ObjectNode root = objectMapper.createObjectNode();
        root.set("units", units);
        root.put("summary", String.format("审查完成：共 %d 单元，成功 %d，失败 %d", total, success, failed));
        record.setResultJson(root.toString());
        record.setStatus(ReviewStatus.resolve(total, success, failed));
        record.setProgress(100);
        record.setFinishedAt(LocalDateTime.now());
        reviewRecordMapper.updateById(record);
    }

    private void markRunning(ReviewRecord record) {
        record.setStatus(ReviewStatus.RUNNING);
        if (record.getStartedAt() == null) {
            record.setStartedAt(LocalDateTime.now());
        }
        record.setProgress(0);
        reviewRecordMapper.updateById(record);
    }

    private void markFailed(ReviewRecord record) {
        record.setStatus(ReviewStatus.FAILED);
        record.setProgress(100);
        record.setFinishedAt(LocalDateTime.now());
        reviewRecordMapper.updateById(record);
    }

    private List<String> parseScope(String scopeJson) throws Exception {
        return objectMapper.readValue(scopeJson == null || scopeJson.isBlank() ? "[]" : scopeJson,
                new TypeReference<List<String>>() {
                });
    }

    private Snapshot parseSnapshot(String snapshotJson) throws Exception {
        JsonNode root = objectMapper.readTree(snapshotJson);
        JsonNode model = root.path("model");
        return new Snapshot(model.path("baseUrl").asText(),
                model.path("apiKey").asText(),
                model.path("modelName").asText());
    }

    /** 找出需要重审的失败文件路径；无结果时回退为全量 scope。 */
    private List<String> failedPathsToRun(ReviewRecord record) throws Exception {
        if (record.getResultJson() == null || record.getResultJson().isBlank()) {
            return parseScope(record.getScopeJson());
        }
        JsonNode root = objectMapper.readTree(record.getResultJson());
        Set<String> failed = new LinkedHashSet<>();
        for (JsonNode u : root.path("units")) {
            if ("failed".equals(u.path("status").asText())) {
                failed.add(u.path("path").asText());
            }
        }
        return new ArrayList<>(failed);
    }

    private record Snapshot(String baseUrl, String apiKey, String modelName) {
    }

    /** 单元执行任务：正常单元带 code，拉取失败单元带 fetchError。 */
    private record UnitTask(String path, String kind, String name, int startLine, int endLine, String code, String fetchError) {
        static UnitTask of(ReviewUnit u) {
            return new UnitTask(u.path(), u.kind(), u.name(), u.startLine(), u.endLine(), u.code(), null);
        }

        static UnitTask fetchFailed(String path, String error) {
            return new UnitTask(path, "file", fileName(path), 1, 1, null, error);
        }

        static UnitTask fallback() {
            return new UnitTask("", "file", "", 1, 1, null, "未知任务");
        }
    }

    private static String fileName(String path) {
        int idx = path.lastIndexOf('/');
        return idx >= 0 ? path.substring(idx + 1) : path;
    }
}
