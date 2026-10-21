package com.codereview.agent.report;

import com.codereview.agent.api.ApiException;
import com.codereview.agent.api.ErrorCode;
import com.codereview.agent.dto.ReportDtos;
import com.codereview.agent.entity.AgentReportSource;
import com.codereview.agent.prompt.PromptFact;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 把冻结来源整理成模型可读的事实与指标。
 *
 * <p>这里只读取 P32.3 保存的 {@code report_source.source_snapshot}，不联查最新结果、反馈、
 * 材料或仓库。结构化结果保留问题、模式、耦合事实及人工反馈；证据正文只取来源引用指向的
 * 已保存片段，不额外读取当前分支。事实文本进入统一组装后再由公共调用做容量判断，
 * 超限直接失败，不在这里截断。
 */
@Service
public class ReportFactsBuilder {

    static final String FACTS_VERSION = "1";

    private final ObjectMapper mapper;

    public ReportFactsBuilder(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public ReportFacts build(List<AgentReportSource> sources) {
        if (sources == null || sources.isEmpty()) {
            throw new ApiException(ErrorCode.REPORT_INPUT_INVALID, "报告没有冻结来源，不能生成正文");
        }
        List<SourceFact> parsed = new ArrayList<>();
        for (AgentReportSource source : sources) {
            parsed.add(parse(source));
        }

        Map<String, Integer> kinds = new LinkedHashMap<>();
        Set<String> taskIds = new LinkedHashSet<>();
        Set<String> versionKeys = new LinkedHashSet<>();
        List<ReportDtos.SourceSummary> summaries = new ArrayList<>();
        int issueTotal = 0;
        FeedbackCounts issueCounts = new FeedbackCounts();
        FeedbackCounts resultCounts = new FeedbackCounts();
        for (SourceFact fact : parsed) {
            kinds.merge(fact.kind(), 1, Integer::sum);
            taskIds.add(fact.taskId());
            versionKeys.add(fact.versionKey());
            summaries.add(fact.summary());
            issueTotal += fact.issueCount();
            issueCounts.add(fact.issueFeedback());
            resultCounts.add(fact.resultFeedback());
        }

        List<PromptFact> facts = new ArrayList<>();
        facts.add(new PromptFact("SUMMARY", summaryText(parsed, kinds, taskIds.size(),
                versionKeys.size(), issueTotal, issueCounts, resultCounts)));
        for (SourceFact fact : parsed) {
            facts.add(new PromptFact("S" + fact.summary().ordinal(), fact.text()));
        }
        List<String> missing = parsed.stream().flatMap(fact -> fact.missing().stream()).toList();
        return new ReportFacts(facts, missing, parsed.size(), taskIds.size(), versionKeys.size(),
                kinds, summaries);
    }

    private String summaryText(List<SourceFact> sources, Map<String, Integer> kinds, int taskCount,
                               int versionCount, int issueTotal, FeedbackCounts issueCounts,
                               FeedbackCounts resultCounts) {
        StringBuilder text = new StringBuilder();
        text.append("来源 ").append(sources.size()).append(" 个；任务 ").append(taskCount)
                .append(" 个；仓库版本 ").append(versionCount).append(" 个；问题 ")
                .append(issueTotal).append(" 个。\n");
        text.append("类型：").append(kinds.entrySet().stream()
                .map(entry -> kindName(entry.getKey()) + " " + entry.getValue())
                .collect(Collectors.joining("、"))).append("。\n");
        text.append("问题级人工反馈：").append(issueCounts.describe()).append("。\n");
        text.append("结果级人工反馈：").append(resultCounts.describe()).append("。\n");
        text.append("仓库版本分组：\n");
        Map<String, List<Integer>> groups = new LinkedHashMap<>();
        for (SourceFact source : sources) {
            groups.computeIfAbsent(source.groupLabel(), ignored -> new ArrayList<>())
                    .add(source.summary().ordinal());
        }
        for (Map.Entry<String, List<Integer>> entry : groups.entrySet()) {
            text.append("- ").append(entry.getKey()).append("：")
                    .append(entry.getValue().stream().map(ordinal -> "S" + ordinal)
                            .collect(Collectors.joining("、"))).append('\n');
        }
        return text.toString();
    }

    private SourceFact parse(AgentReportSource row) {
        JsonNode snapshot = readObject(row.getSourceSnapshot(), "报告来源");
        String resultId = text(snapshot, "resultId");
        String taskId = text(snapshot, "taskId");
        String kind = text(snapshot, "kind");
        if (blank(resultId) || blank(taskId) || blank(kind)) {
            throw invalid("报告来源缺少结果、任务或类型标识");
        }
        JsonNode project = object(snapshot, "project");
        JsonNode task = object(snapshot, "task");
        JsonNode target = object(snapshot, "target");
        JsonNode result = object(snapshot, "result");
        JsonNode body = result.path("body");
        JsonNode provenance = result.path("provenance");
        if (body.isMissingNode() || body.isNull()) {
            throw invalid("报告来源缺少结果正文");
        }
        Map<String, JsonNode> materials = materials(snapshot);
        List<String> missing = new ArrayList<>();
        List<String> evidence = new ArrayList<>();
        Set<String> evidenceKeys = new LinkedHashSet<>();

        String projectId = text(project, "id");
        String projectName = text(project, "name");
        String repoUrl = text(project, "repoUrl");
        String branch = text(task, "branch");
        String commitSha = text(task, "commitSha");
        String baseSha = text(task, "baseSha");
        String headSha = text(target, "headSha");
        if (blank(headSha)) {
            headSha = commitSha;
        }
        String path = text(target, "path");
        String oldPath = text(target, "oldPath");
        String versionKey = projectId + "|" + nullToEmpty(headSha) + "|" + nullToEmpty(baseSha);
        String groupLabel = (blank(projectName) ? nullToUnknown(projectId) : projectName)
                + "@" + shortSha(headSha)
                + (blank(baseSha) ? "" : "（父 " + shortSha(baseSha) + "）");

        String summary = summaryOf(kind, body);
        List<JsonNode> issues = array(snapshot.path("issues"));
        List<JsonNode> patterns = array(body.path("patterns"));
        List<JsonNode> observations = array(body.path("observations"));
        List<String> limitations = new ArrayList<>(strings(body.path("limitations")));
        JsonNode couplingFacts = provenance.path("couplingFacts");
        if (couplingFacts.isObject()) {
            limitations.addAll(strings(couplingFacts.path("limitations")));
        }

        FeedbackCounts issueFeedback = new FeedbackCounts();
        FeedbackCounts resultFeedback = new FeedbackCounts();
        if ("ISSUE".equals(kind)) {
            for (JsonNode issue : issues) {
                issueFeedback.add(feedbackCount(issue.path("feedback")));
                collectEvidence(issue.path("locations"), materials, evidenceKeys, evidence, missing);
            }
        } else {
            resultFeedback = feedbackCount(snapshot.path("resultFeedback"));
            for (JsonNode pattern : patterns) {
                collectEvidence(pattern.path("evidence"), materials, evidenceKeys, evidence, missing);
            }
            for (JsonNode observation : observations) {
                collectEvidence(observation.path("evidence"), materials, evidenceKeys, evidence, missing);
            }
        }

        ReportDtos.SourceSummary sourceSummary = new ReportDtos.SourceSummary(
                row.getOrdinal(), resultId, kind, projectId, projectName, taskId, text(task, "route"),
                path, headSha, baseSha, displaySummary(summary), issues.size(),
                issueFeedback.accurate, issueFeedback.inaccurate, issueFeedback.unknown,
                issueFeedback.unassessed);
        String facts = sourceText(projectName, repoUrl, task, path, oldPath, branch, commitSha,
                baseSha, headSha, summary, issues, patterns, observations, limitations, couplingFacts,
                resultFeedback, evidence, missing);
        return new SourceFact(kind, taskId, versionKey, groupLabel, sourceSummary, issueFeedback,
                resultFeedback, issues.size(), facts, missing);
    }

    private String sourceText(String projectName, String repoUrl, JsonNode task, String path,
                              String oldPath, String branch, String commitSha, String baseSha,
                              String headSha, String summary, List<JsonNode> issues,
                              List<JsonNode> patterns, List<JsonNode> observations,
                              List<String> limitations, JsonNode couplingFacts,
                              FeedbackCounts resultFeedback, List<String> evidence,
                              List<String> missing) {
        StringBuilder text = new StringBuilder();
        text.append("项目：").append(nullToUnknown(projectName)).append("｜仓库：")
                .append(nullToUnknown(repoUrl)).append("｜版本：").append(shortSha(headSha));
        if (!blank(baseSha)) {
            text.append("（父 ").append(shortSha(baseSha)).append("）");
        }
        text.append('\n');
        text.append("任务：").append(text(task, "id")).append("｜状态：").append(text(task, "state"))
                .append("｜来源：").append(text(task, "sourceKind"));
        if (!blank(branch)) {
            text.append("｜分支：").append(branch);
        }
        if (!blank(commitSha)) {
            text.append("｜提交：").append(shortSha(commitSha));
        }
        text.append('\n');
        text.append("文件：").append(nullToUnknown(path));
        if (!blank(oldPath)) {
            text.append("（旧路径：").append(oldPath).append("）");
        }
        text.append('\n');
        text.append("结论：").append(nullToEmpty(summary)).append('\n');
        if (!issues.isEmpty()) {
            text.append("问题 ").append(issues.size()).append(" 个：\n");
            for (JsonNode issue : issues) {
                text.append("  - ").append(text(issue, "ordinal")).append(". [")
                        .append(text(issue, "severity")).append('/').append(text(issue, "category"))
                        .append("] ").append(text(issue, "title")).append("（")
                        .append(locations(issue.path("locations"))).append("）\n");
                text.append("    原因：").append(nullToEmpty(text(issue, "reason"))).append('\n');
                text.append("    建议：").append(nullToEmpty(text(issue, "suggestion"))).append('\n');
                text.append("    反馈：").append(feedbackText(issue.path("feedback"))).append('\n');
            }
        }
        if (!patterns.isEmpty()) {
            text.append("模式候选 ").append(patterns.size()).append(" 个：\n");
            for (JsonNode pattern : patterns) {
                text.append("  - ").append(nullToEmpty(text(pattern, "name"))).append("（")
                        .append(nullToEmpty(text(pattern, "family"))).append("，角色：")
                        .append(nullToEmpty(text(pattern, "role"))).append("）：")
                        .append(nullToEmpty(text(pattern, "explanation"))).append('\n');
            }
        }
        if (!observations.isEmpty()) {
            text.append("耦合观察 ").append(observations.size()).append(" 个：\n");
            for (JsonNode observation : observations) {
                text.append("  - ").append(nullToEmpty(text(observation, "title"))).append("：")
                        .append(nullToEmpty(text(observation, "explanation")));
                List<String> dependencyIds = strings(observation.path("dependencyIds"));
                if (!dependencyIds.isEmpty()) {
                    text.append("；依赖：").append(String.join("、", dependencyIds));
                }
                text.append('\n');
            }
        }
        if (couplingFacts.isObject()) {
            text.append("耦合指标：已解析出向依赖 ")
                    .append(couplingFacts.path("resolvedOutgoingCount").asInt())
                    .append(" 个，未解析类型 ").append(couplingFacts.path("unresolvedTypeCount").asInt())
                    .append(" 个，覆盖 ").append(couplingFacts.path("coverage").asText("UNKNOWN"))
                    .append("。\n");
            for (JsonNode edge : array(couplingFacts.path("edges"))) {
                text.append("  - ").append(text(edge, "id")).append(" ").append(text(edge, "sourceType"))
                        .append(" -> ").append(text(edge, "targetType")).append("（")
                        .append(text(edge, "relation")).append("，").append(text(edge, "targetPath"))
                        .append(':').append(text(edge, "line")).append("）\n");
            }
        }
        if (!limitations.isEmpty()) {
            text.append("限制：\n");
            for (String limitation : limitations) {
                text.append("  - ").append(limitation).append('\n');
            }
        }
        text.append("结果级反馈：").append(resultFeedback.describe()).append('\n');
        if (!evidence.isEmpty()) {
            text.append("已保存代码证据：\n");
            for (String item : evidence) {
                text.append(item).append('\n');
            }
        }
        if (!missing.isEmpty()) {
            text.append("材料缺口：\n");
            for (String item : missing) {
                text.append("  - ").append(item).append('\n');
            }
        }
        return text.toString();
    }

    private void collectEvidence(JsonNode locations, Map<String, JsonNode> materials,
                                 Set<String> keys, List<String> evidence, List<String> missing) {
        for (JsonNode location : array(locations)) {
            String materialId = text(location, "materialId");
            JsonNode material = blank(materialId) ? null : materials.get(materialId);
            String locationPath = text(location, "path");
            if (material == null && !blank(locationPath)) {
                material = materials.values().stream()
                        .filter(item -> locationPath.equals(text(item, "path")))
                        .findFirst().orElse(null);
            }
            if (material == null) {
                missing.add("位置 " + locations(location) + " 未关联保存材料，代码原文需在冻结来源中查看");
                continue;
            }
            int start = location.path("startLine").asInt(material.path("startLine").asInt(1));
            int end = location.path("endLine").asInt(start);
            String key = text(material, "id") + ":" + start + "-" + end;
            if (!keys.add(key)) {
                continue;
            }
            String excerpt = lines(material, start, end);
            if (excerpt == null) {
                missing.add("材料 " + text(material, "path") + " 在 " + start + "-" + end
                        + " 行没有保存正文");
                continue;
            }
            evidence.add("  - " + text(material, "path") + " 行 " + start + "-" + end
                    + "（材料 " + text(material, "id") + "，SHA " + shortSha(text(material, "sha"))
                    + "）：\n" + excerpt);
        }
    }

    private String lines(JsonNode material, int start, int end) {
        String content = text(material, "content");
        if (content == null || content.isEmpty()) {
            return null;
        }
        int materialStart = material.path("startLine").asInt(1);
        String[] lines = content.split("\n", -1);
        int from = start - materialStart;
        int to = end - materialStart;
        if (from < 0 || to < from || from >= lines.length) {
            return null;
        }
        to = Math.min(to, lines.length - 1);
        StringBuilder text = new StringBuilder();
        for (int index = from; index <= to; index++) {
            text.append("    ").append(String.format("%6d | %s", materialStart + index, lines[index]))
                    .append('\n');
        }
        return text.toString();
    }

    private Map<String, JsonNode> materials(JsonNode snapshot) {
        Map<String, JsonNode> result = new LinkedHashMap<>();
        for (JsonNode material : array(snapshot.path("materials"))) {
            String id = text(material, "id");
            if (!blank(id)) {
                result.put(id, material);
            }
        }
        return result;
    }

    private String locations(JsonNode locations) {
        if (!locations.isArray() || locations.isEmpty()) {
            return "位置未保存";
        }
        List<String> parts = new ArrayList<>();
        for (JsonNode location : locations) {
            parts.add(text(location, "path") + ":" + location.path("startLine").asInt()
                    + "-" + location.path("endLine").asInt());
        }
        return String.join("、", parts);
    }

    private String feedbackText(JsonNode feedback) {
        if (feedback == null || feedback.isNull() || feedback.isMissingNode()) {
            return "未反馈";
        }
        String assessment = text(feedback, "assessment");
        String comment = text(feedback, "comment");
        return (blank(assessment) ? "未判定" : assessment) + (blank(comment) ? "" : "（" + comment + "）");
    }

    private FeedbackCounts feedbackCount(JsonNode feedback) {
        FeedbackCounts count = new FeedbackCounts();
        String assessment = feedback == null || feedback.isNull() ? null : text(feedback, "assessment");
        if ("ACCURATE".equals(assessment)) {
            count.accurate++;
        } else if ("INACCURATE".equals(assessment)) {
            count.inaccurate++;
        } else if ("UNKNOWN".equals(assessment)) {
            count.unknown++;
        } else {
            count.unassessed++;
        }
        return count;
    }

    private String summaryOf(String kind, JsonNode body) {
        if ("AGENT".equals(kind)) {
            return body.isTextual() ? body.asText() : "";
        }
        return text(body, "summary");
    }

    private String displaySummary(String summary) {
        String value = summary == null ? "" : summary.strip();
        int lineBreak = value.indexOf('\n');
        if (lineBreak >= 0) {
            value = value.substring(0, lineBreak);
        }
        return value.length() <= 200 ? value : value.substring(0, 200) + "…";
    }

    private JsonNode readObject(String value, String label) {
        if (blank(value)) {
            throw invalid(label + "为空");
        }
        try {
            JsonNode node = mapper.readTree(value);
            if (!node.isObject()) {
                throw invalid(label + "不是对象");
            }
            return node;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw invalid(label + "不是合法 JSON");
        }
    }

    private static JsonNode object(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isObject() ? value : JsonNodeFactory.instance.objectNode();
    }

    private static List<JsonNode> array(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        List<JsonNode> values = new ArrayList<>();
        node.forEach(values::add);
        return values;
    }

    private static List<String> strings(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        node.forEach(value -> {
            if (value.isTextual() && !value.asText().isBlank()) {
                values.add(value.asText());
            }
        });
        return values;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String nullToUnknown(String value) {
        return blank(value) ? "UNKNOWN" : value;
    }

    private static String shortSha(String sha) {
        return blank(sha) ? "UNKNOWN" : sha.length() <= 12 ? sha : sha.substring(0, 12);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static ApiException invalid(String message) {
        return new ApiException(ErrorCode.REPORT_INPUT_INVALID, message);
    }

    private static String kindName(String kind) {
        return switch (kind) {
            case "ISSUE" -> "问题审查";
            case "PATTERN" -> "设计模式";
            case "COUPLING" -> "耦合度";
            case "AGENT" -> "Agent";
            default -> kind;
        };
    }

    /** 组装后的事实包；来源摘要用于详情页，不回写数据库。 */
    public record ReportFacts(List<PromptFact> facts, List<String> missingNotes, int sourceCount,
                              int taskCount, int versionCount, Map<String, Integer> kindCounts,
                              List<ReportDtos.SourceSummary> summaries) {
    }

    private record SourceFact(String kind, String taskId, String versionKey, String groupLabel,
                              ReportDtos.SourceSummary summary, FeedbackCounts issueFeedback,
                              FeedbackCounts resultFeedback, int issueCount, String text,
                              List<String> missing) {
    }

    private static final class FeedbackCounts {
        int accurate;
        int inaccurate;
        int unknown;
        int unassessed;

        void add(FeedbackCounts other) {
            accurate += other.accurate;
            inaccurate += other.inaccurate;
            unknown += other.unknown;
            unassessed += other.unassessed;
        }

        String describe() {
            return "准确 " + accurate + " / 不准确 " + inaccurate + " / 无法判断 " + unknown
                    + " / 未反馈 " + unassessed;
        }
    }
}
