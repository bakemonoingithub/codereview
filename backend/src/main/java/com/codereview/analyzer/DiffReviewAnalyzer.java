package com.codereview.analyzer;

import com.codereview.config.ReviewProperties;
import com.codereview.diff.DiffReviewUnit;
import com.codereview.diff.DiffUnitBuilder;
import com.codereview.git.ChangedFile;
import com.codereview.git.CommitDetail;
import com.codereview.git.GitHostClient;
import com.codereview.llm.LlmClient;
import com.codereview.review.RetryPolicy;
import com.codereview.review.ReviewStatus;
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
 * diff-review 分析器（analyzer_type=5）：审查「某次提交引入了什么改动」。
 * <p>
 * 与 llm-review 的区别：输入不是整文件，而是**变更意图 + 方法级变更片段**；
 * 输出除 issues 外还给出 {@code intentVerdict}（改动是否达成提交信息声称的目的）。
 * <p>
 * result_json = {@code {commit:{...}, units:[{path, unit:{kind,name,lines}, status, changeType,
 * intentVerdict, intentNote, issues[], summary, raw}], summary}}
 */
@Component
public class DiffReviewAnalyzer implements Analyzer {

    /** 单元数上限（护栏）：超出直接失败并提示分批，避免一个巨型提交打爆全局并发额度。 */
    public static final int MAX_UNITS = 50;

    /** 方法体窗口默认值：变更行 ± N 行。 */
    private static final int DEFAULT_WINDOW_LINES = 60;

    private static final List<String> VERDICTS = List.of("符合", "部分符合", "不符", "无法判断");

    private static final String SYSTEM_PROMPT =
            "你是资深代码审查助手，正在做「变更走查」：审查他人某次提交引入的改动。只输出合法 JSON，不要输出任何其他文字。";

    private static final String USER_TEMPLATE = """
            请判断下面这次改动是否达成了提交信息声称的目的、是否有遗漏（例如改了接口却没改调用方），
            并找出这次改动引入的问题（缺陷、业务规则、设计问题、命名规范）。

            以 JSON 返回，格式：
            {"intentVerdict":"符合|部分符合|不符|无法判断","intentNote":"一句话说明意图达成情况",
             "issues":[{"severity":"MAJOR|MINOR|INFO","category":"...","newLine":新文件行号(数字),"title":"...","description":"...","suggestion":"..."}],
             "summary":"一句话概述"}

            [变更意图]
            %s

            [单元 %d/%d] %s
            变更类型: %s   范围: 新侧第 %d-%d 行

            代码（行首 + 为新增行，- 为删除行，数字为新文件行号）：
            %s""";

    private final GitHostClient gitHostClient;
    private final LlmClient llmClient;
    private final ThreadPoolTaskExecutor unitExecutor;
    private final ReviewProperties props;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DiffReviewAnalyzer(GitHostClient gitHostClient, LlmClient llmClient,
                              @Qualifier("reviewUnitExecutor") ThreadPoolTaskExecutor unitExecutor,
                              ReviewProperties props) {
        this.gitHostClient = gitHostClient;
        this.llmClient = llmClient;
        this.unitExecutor = unitExecutor;
        this.props = props;
    }

    @Override
    public int type() {
        return 5;
    }

    @Override
    public AnalyzeOutcome analyze(AnalysisContext ctx) {
        String sha = ctx.record().getCommitSha();
        if (sha == null || sha.isBlank()) {
            return failure("未指定提交：diff 审查必须锚定到具体提交（commitSha 为空）");
        }
        CommitDetail detail;
        try {
            detail = gitHostClient.commitDetail(ctx.project().getCredential(), ctx.project().getCredentialType(),
                    ctx.ref().owner(), ctx.ref().repo(), sha);
        } catch (Exception e) {
            return failure("拉取单提交详情失败: " + e.getMessage());
        }

        List<ChangedFile> files = selectFiles(detail, ctx.scope());
        if (files.isEmpty()) {
            return failure(detail.truncated()
                    ? "该提交变更文件过多（宿主只返回了前 300 个），且选中的文件不在返回列表中"
                    : "该提交没有可审查的变更文件");
        }

        DiffUnitBuilder builder = new DiffUnitBuilder(windowLines(ctx.params()), props.getChunkMaxChars());
        List<UnitTask> tasks = new ArrayList<>();
        for (ChangedFile file : files) {
            tasks.addAll(buildTasks(ctx, builder, file, sha));
        }
        if (tasks.isEmpty()) {
            return failure("未能构建任何审查单元（所有变更文件都取不到可用内容）");
        }
        if (tasks.size() > MAX_UNITS) {
            return failure(String.format(
                    "本次变更将产生 %d 个审查单元，超过上限 %d 个；请减少勾选的变更文件，或分批审查",
                    tasks.size(), MAX_UNITS));
        }
        for (int i = 0; i < tasks.size(); i++) {
            UnitTask t = tasks.get(i);
            tasks.set(i, new UnitTask(t.unit(), t.file(),
                    buildUserPrompt(t.unit(), t.file(), detail, i + 1, tasks.size())));
        }

        // 并行限流执行（单元级重试）
        ArrayNode results = objectMapper.createArrayNode();
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
                results.add(failedUnit(null, "执行异常: " + e.getMessage()));
            }
            done++;
            ctx.progress().accept(done * 100 / submitted);
        }

        int success = 0;
        int failed = 0;
        for (JsonNode u : results) {
            if ("success".equals(u.path("status").asText())) {
                success++;
            } else {
                failed++;
            }
        }
        ObjectNode root = objectMapper.createObjectNode();
        root.set("commit", commitNode(detail));
        root.set("units", results);
        root.put("summary", String.format("变更审查完成：共 %d 单元，成功 %d，失败 %d",
                results.size(), success, failed));
        return new AnalyzeOutcome(root, ReviewStatus.resolve(results.size(), success, failed));
    }

    // ------------------------------------------------------------------
    // 单元构建
    // ------------------------------------------------------------------

    private List<UnitTask> buildTasks(AnalysisContext ctx, DiffUnitBuilder builder, ChangedFile file, String sha) {
        List<DiffReviewUnit> units = new ArrayList<>();
        if (file.hasPatch()) {
            // 删除的文件在新侧已不存在，无需拉全文；非 Java 也无法做方法级补全
            String content = (!file.removed() && isJava(file.path()))
                    ? tryRawFile(ctx, sha, file.path()) : null;
            units.addAll(builder.buildFromPatch(file.path(), file.status(), file.patch(), content));
        }
        if (units.isEmpty()) {
            // 全文件兜底：patch 缺失（二进制/过大）或 patch 不可解析
            String content = tryRawFile(ctx, sha, file.path());
            if (content != null) {
                units.add(builder.buildFromFullFile(file.path(), file.status(), content));
            }
        }
        List<UnitTask> tasks = new ArrayList<>();
        for (DiffReviewUnit u : units) {
            tasks.add(new UnitTask(u, file, null));
        }
        return tasks;
    }

    private String tryRawFile(AnalysisContext ctx, String sha, String path) {
        try {
            return gitHostClient.rawFile(ctx.project().getCredential(), ctx.project().getCredentialType(),
                    ctx.ref().owner(), ctx.ref().repo(), sha, path);
        } catch (Exception e) {
            return null;
        }
    }

    private static List<ChangedFile> selectFiles(CommitDetail detail, List<String> selected) {
        List<ChangedFile> all = detail.files() == null ? List.of() : detail.files();
        if (selected == null || selected.isEmpty()) {
            return all;
        }
        Set<String> want = new LinkedHashSet<>(selected);
        List<ChangedFile> out = new ArrayList<>();
        for (ChangedFile f : all) {
            if (want.contains(f.path())) {
                out.add(f);
            }
        }
        return out;
    }

    private static int windowLines(JsonNode params) {
        int n = params == null ? 0 : params.path("methodWindowLines").asInt(0);
        return n > 0 ? n : DEFAULT_WINDOW_LINES;
    }

    private static boolean isJava(String path) {
        return path != null && path.endsWith(".java");
    }

    private record UnitTask(DiffReviewUnit unit, ChangedFile file, String prompt) {
    }

    // ------------------------------------------------------------------
    // 提示词
    // ------------------------------------------------------------------

    private String buildUserPrompt(DiffReviewUnit unit, ChangedFile file, CommitDetail detail, int index, int total) {
        String changeLabel = String.format("%s（+%s/-%s）",
                unit.changeType() == null ? "modified" : unit.changeType(),
                file == null || file.additions() == null ? "?" : file.additions(),
                file == null || file.deletions() == null ? "?" : file.deletions());
        String header = unit.header() + (unit.truncated() ? "（方法体已按窗口截断）" : "")
                + (unit.note() == null ? "" : "（" + unit.note() + "）");
        return String.format(USER_TEMPLATE, intentBlock(detail), index, total, header, changeLabel,
                unit.startLine(), unit.endLine(), unit.text());
    }

    private static String intentBlock(CommitDetail detail) {
        StringBuilder sb = new StringBuilder();
        sb.append("提交: ").append(shortSha(detail.sha()));
        if (detail.merge()) {
            sb.append("（merge 提交，与第一父提交 ").append(shortSha(detail.baseSha())).append(" 对比）");
        } else if (detail.baseSha() != null) {
            sb.append("（与父提交 ").append(shortSha(detail.baseSha())).append(" 对比）");
        }
        sb.append('\n');
        sb.append("作者: ").append(blankTo(detail.author(), "未知"))
                .append("    时间: ").append(blankTo(detail.date(), "未知")).append('\n');
        sb.append("提交信息: ").append(blankTo(detail.message(), "（提交信息为空）")).append('\n');
        sb.append("统计: ").append(detail.fileCount()).append(" 文件, +")
                .append(detail.additions() == null ? "?" : detail.additions()).append("/-")
                .append(detail.deletions() == null ? "?" : detail.deletions());
        if (detail.truncated()) {
            sb.append("\n注意: 该提交变更文件过多，宿主只返回了前 300 个，文件列表可能不完整");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 执行与结果
    // ------------------------------------------------------------------

    private ObjectNode executeUnit(AnalysisContext ctx, UnitTask task) {
        int attempt = 0;
        while (true) {
            try {
                String content = llmClient.chatJson(ctx.baseUrl(), ctx.apiKey(), ctx.modelName(),
                        SYSTEM_PROMPT, task.prompt());
                return successUnit(task.unit(), content);
            } catch (Exception e) {
                if (attempt >= props.getRetryMax() || !RetryPolicy.isRetryable(e)) {
                    return failedUnit(task.unit(), e.getMessage());
                }
                sleep(RetryPolicy.backoffMillis(attempt));
                attempt++;
            }
        }
    }

    private ObjectNode successUnit(DiffReviewUnit unit, String content) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("path", unit.path());
        ObjectNode u = node.putObject("unit");
        u.put("kind", unit.kind());
        u.put("name", unit.name());
        u.put("lines", unit.startLine() + "-" + unit.endLine());
        node.put("status", "success");
        node.put("changeType", unit.changeType());
        node.put("raw", content);
        if (unit.truncated()) {
            node.put("truncated", true);
        }
        if (unit.note() != null) {
            node.put("note", unit.note());
        }
        try {
            JsonNode parsed = objectMapper.readTree(content);
            node.set("issues", normalizeIssues(parsed.path("issues")));
            node.put("summary", parsed.path("summary").asText(""));
            node.put("intentVerdict", normalizeVerdict(parsed.path("intentVerdict").asText(null)));
            if (parsed.hasNonNull("intentNote")) {
                node.put("intentNote", parsed.path("intentNote").asText());
            }
        } catch (Exception e) {
            node.putArray("issues");
            node.put("summary", "");
            node.put("intentVerdict", "无法判断");
        }
        return node;
    }

    private ObjectNode failedUnit(DiffReviewUnit unit, String error) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("path", unit == null ? "" : unit.path());
        if (unit != null) {
            ObjectNode u = node.putObject("unit");
            u.put("kind", unit.kind());
            u.put("name", unit.name());
            u.put("lines", unit.startLine() + "-" + unit.endLine());
        }
        node.put("status", "failed");
        node.put("error", error == null ? "未知错误" : error);
        return node;
    }

    private AnalyzeOutcome failure(String message) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("error", message);
        root.putArray("units");
        root.put("summary", message);
        return new AnalyzeOutcome(root, ReviewStatus.FAILED);
    }

    private ObjectNode commitNode(CommitDetail detail) {
        ObjectNode c = objectMapper.createObjectNode();
        c.put("sha", detail.sha());
        c.put("baseSha", detail.baseSha());
        c.put("merge", detail.merge());
        c.put("message", detail.message());
        c.put("author", detail.author());
        c.put("date", detail.date());
        c.put("files", detail.fileCount());
        if (detail.additions() != null) {
            c.put("additions", detail.additions());
        }
        if (detail.deletions() != null) {
            c.put("deletions", detail.deletions());
        }
        c.put("truncated", detail.truncated());
        return c;
    }

    /** issues 归一化：统一给出 newLine，并保留 line 以兼容旧前端解析。 */
    private ArrayNode normalizeIssues(JsonNode issues) {
        ArrayNode out = objectMapper.createArrayNode();
        if (issues == null || !issues.isArray()) {
            return out;
        }
        for (JsonNode i : issues) {
            ObjectNode o = out.addObject();
            o.put("severity", i.path("severity").asText("INFO"));
            if (i.hasNonNull("category")) {
                o.put("category", i.path("category").asText());
            }
            Integer line = intOrNull(i, "newLine");
            if (line == null) {
                line = intOrNull(i, "line");
            }
            if (line != null) {
                o.put("newLine", line);
                o.put("line", line);
            }
            o.put("title", i.path("title").asText(""));
            if (i.hasNonNull("description")) {
                o.put("description", i.path("description").asText());
            }
            if (i.hasNonNull("suggestion")) {
                o.put("suggestion", i.path("suggestion").asText());
            }
        }
        return out;
    }

    /** 意图结论归一化到四态（Q33）。 */
    static String normalizeVerdict(String raw) {
        if (raw == null || raw.isBlank()) {
            return "无法判断";
        }
        String v = raw.trim();
        for (String candidate : VERDICTS) {
            if (candidate.equals(v)) {
                return candidate;
            }
        }
        if (v.contains("部分")) {
            return "部分符合";
        }
        if (v.contains("不符") || v.contains("不符合")) {
            return "不符";
        }
        if (v.contains("符合")) {
            return "符合";
        }
        return "无法判断";
    }

    private static Integer intOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return (v.isMissingNode() || v.isNull() || !v.isNumber()) ? null : v.asInt();
    }

    private static String shortSha(String sha) {
        if (sha == null || sha.isBlank()) {
            return "?";
        }
        return sha.substring(0, Math.min(7, sha.length()));
    }

    private static String blankTo(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value.trim();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
