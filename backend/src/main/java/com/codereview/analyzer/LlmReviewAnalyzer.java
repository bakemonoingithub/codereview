package com.codereview.analyzer;

import com.codereview.chunk.Chunker;
import com.codereview.chunk.ReviewUnit;
import com.codereview.config.ReviewProperties;
import com.codereview.git.GitHostClient;
import com.codereview.llm.LlmClient;
import com.codereview.review.ReviewStatus;
import com.codereview.review.RetryPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;

/**
 * llm-review 分析器：拉取 + 分块制备单元 → 并行限流执行（单元级重试）→ 聚合。
 * result_json = {units:[{path, unit:{kind,name,lines}, status, issues[], summary, error}], summary}
 */
@Component
public class LlmReviewAnalyzer implements Analyzer {

    private static final String SYSTEM_PROMPT =
            "你是资深代码审查助手，只输出合法 JSON，不要输出任何其他文字。";
    private static final String USER_TEMPLATE =
            "请审查下面的 Java 代码单元，找出问题（命名规范、代码缺陷、业务规则、设计问题），"
                    + "并以 JSON 返回，格式：{\"issues\":[{\"severity\":\"MAJOR|MINOR|INFO\","
                    + "\"category\":\"...\",\"line\":行号,\"title\":\"...\",\"description\":\"...\","
                    + "\"suggestion\":\"...\"}],\"summary\":\"一句话概述\"}。\n\n单元信息：%s\n代码：\n%s";

    private final GitHostClient gitHostClient;
    private final LlmClient llmClient;
    private final ThreadPoolTaskExecutor unitExecutor;
    private final ReviewProperties props;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public LlmReviewAnalyzer(GitHostClient gitHostClient, LlmClient llmClient,
                             @Qualifier("reviewUnitExecutor") ThreadPoolTaskExecutor unitExecutor,
                             ReviewProperties props) {
        this.gitHostClient = gitHostClient;
        this.llmClient = llmClient;
        this.unitExecutor = unitExecutor;
        this.props = props;
    }

    @Override
    public int type() {
        return 1;
    }

    @Override
    public AnalyzeOutcome analyze(AnalysisContext ctx) {
        ArrayNode units = runPaths(ctx, ctx.scope());
        return outcome(units);
    }

    @Override
    public AnalyzeOutcome retry(AnalysisContext ctx, JsonNode oldResult) {
        Set<String> failedPaths = failedPaths(oldResult, ctx.scope());
        if (failedPaths.isEmpty()) {
            return new AnalyzeOutcome(oldResult, ReviewStatus.SUCCESS);
        }
        ArrayNode newUnits = runPaths(ctx, new ArrayList<>(failedPaths));
        ArrayNode merged = objectMapper.createArrayNode();
        if (oldResult != null) {
            for (JsonNode u : oldResult.path("units")) {
                if (!failedPaths.contains(u.path("path").asText())) {
                    merged.add(u);
                }
            }
        }
        newUnits.forEach(merged::add);
        return outcome(merged);
    }

    private ArrayNode runPaths(AnalysisContext ctx, List<String> paths) {
        List<UnitTask> tasks = new ArrayList<>();
        for (String path : paths) {
            try {
                String code = gitHostClient.rawFile(ctx.project().getCredential(), ctx.project().getCredentialType(), ctx.ref().owner(), ctx.ref().repo(), ctx.branch(), path);
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
            cs.submit(() -> executeUnit(ctx, task));
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
            ctx.progress().accept(done * 100 / submitted);
        }
        return results;
    }

    private ObjectNode executeUnit(AnalysisContext ctx, UnitTask task) {
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
                String content = llmClient.chatJson(ctx.baseUrl(), ctx.apiKey(), ctx.modelName(),
                        SYSTEM_PROMPT, buildUserPrompt(ctx, task));
                JsonNode result = objectMapper.readTree(content);
                return successUnit(task, result);
            } catch (Throwable e) {
                last = e;
                if (!RetryPolicy.isRetryable(e)) {
                    break;
                }
            }
        }
        return failedUnit(task, last == null ? "未知错误" : last.getMessage());
    }

    private AnalyzeOutcome outcome(ArrayNode units) {
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
        return new AnalyzeOutcome(root, ReviewStatus.resolve(total, success, failed));
    }

    private Set<String> failedPaths(JsonNode oldResult, List<String> scope) {
        Set<String> failed = new LinkedHashSet<>();
        if (oldResult == null) {
            failed.addAll(scope);
            return failed;
        }
        for (JsonNode u : oldResult.path("units")) {
            if ("failed".equals(u.path("status").asText())) {
                failed.add(u.path("path").asText());
            }
        }
        return failed;
    }

    private String buildUserPrompt(AnalysisContext ctx, UnitTask task) {
        String header = String.format("{文件路径:%s, 类型:%s, 名称:%s, 行范围:%d-%d}",
                task.path(), task.kind(), task.name(), task.startLine(), task.endLine());
        String prompt = String.format(USER_TEMPLATE, header, task.code());
        if (ctx.customPrompt() != null && !ctx.customPrompt().isBlank()) {
            prompt = prompt + "\n\n关注点规则：\n" + ctx.customPrompt();
        }
        return prompt;
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
