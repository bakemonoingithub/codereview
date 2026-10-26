package com.codereview.agent.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code submit_result} 的结构与引用校验。
 *
 * <p>只检查结构和位置：引用必须指向实际送入过模型的材料，行号必须落在材料范围内，
 * 正文里的 {@code [E#]} 必须都能解析；不判断结论语义是否正确。
 * 未使用的引用不发布；正文引用不到的材料不会被自动放大。
 */
public record AgentSubmission(String title, String summary, String answerStatus, String markdown,
                              List<Citation> citations, List<String> unresolved) {

    public static final String ANSWERED = "ANSWERED";
    public static final String INSUFFICIENT_EVIDENCE = "INSUFFICIENT_EVIDENCE";

    private static final Set<String> FIELDS =
            Set.of("title", "summary", "answerStatus", "markdown", "citations", "unresolved");
    private static final Set<String> CITATION_FIELDS =
            Set.of("id", "materialId", "startLine", "endLine");
    private static final Pattern MARKER = Pattern.compile("\\[E(\\d+)]");
    /** citations 最多 100 项；将每一项校验错误都返回给模型，避免逐条试错。 */
    private static final int MAX_VALIDATION_DETAILS = 100;

    public AgentSubmission {
        citations = citations == null ? List.of() : List.copyOf(citations);
        unresolved = unresolved == null ? List.of() : List.copyOf(unresolved);
    }

    public boolean answered() {
        return ANSWERED.equals(answerStatus);
    }

    public record Citation(String id, String materialId, int startLine, int endLine) {
    }

    /** 已成功送入模型请求的材料；只有这些材料能被引用。 */
    public record SentMaterial(String materialId, String path, String side, String sha,
                               Integer startLine, Integer endLine, String contentHash) {
    }

    public static AgentSubmission parse(ObjectMapper mapper, String json) {
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new AgentProtocolException("RESULT_INVALID", "submit_result 参数不是合法 JSON");
        }
        if (root == null || !root.isObject()) {
            throw new AgentProtocolException("RESULT_INVALID", "submit_result 参数必须是 JSON 对象");
        }
        root.fieldNames().forEachRemaining(field -> {
            if (!FIELDS.contains(field)) {
                throw new AgentProtocolException("RESULT_INVALID", "submit_result 含未知字段：" + field);
            }
        });
        String title = text(root, "title", 200);
        String summary = text(root, "summary", 1000);
        String markdown = text(root, "markdown", 24000);
        String status = text(root, "answerStatus", 32);
        if (!ANSWERED.equals(status) && !INSUFFICIENT_EVIDENCE.equals(status)) {
            throw new AgentProtocolException("RESULT_INVALID",
                    "answerStatus 只能是 ANSWERED 或 INSUFFICIENT_EVIDENCE");
        }
        List<Citation> citations = new ArrayList<>();
        JsonNode citationNode = root.path("citations");
        if (!citationNode.isMissingNode() && !citationNode.isNull()) {
            if (!citationNode.isArray()) {
                throw new AgentProtocolException("RESULT_INVALID", "citations 必须是数组");
            }
            if (citationNode.size() > 100) {
                throw new AgentProtocolException("RESULT_INVALID", "citations 最多 100 项");
            }
            for (JsonNode item : citationNode) {
                if (!item.isObject()) {
                    throw new AgentProtocolException("RESULT_INVALID", "citation 必须是对象");
                }
                item.fieldNames().forEachRemaining(field -> {
                    if (!CITATION_FIELDS.contains(field)) {
                        throw new AgentProtocolException("RESULT_INVALID", "citation 含未知字段：" + field);
                    }
                });
                String id = citationText(item, "id");
                String materialId = citationText(item, "materialId");
                int start = citationInt(item, "startLine");
                int end = citationInt(item, "endLine");
                citations.add(new Citation(id, materialId, start, end));
            }
        }
        List<String> unresolved = new ArrayList<>();
        JsonNode unresolvedNode = root.path("unresolved");
        if (!unresolvedNode.isMissingNode() && !unresolvedNode.isNull()) {
            if (!unresolvedNode.isArray()) {
                throw new AgentProtocolException("RESULT_INVALID", "unresolved 必须是数组");
            }
            if (unresolvedNode.size() > 20) {
                throw new AgentProtocolException("RESULT_INVALID", "unresolved 最多 20 项");
            }
            for (JsonNode item : unresolvedNode) {
                if (!item.isTextual() || item.asText().isBlank() || item.asText().length() > 1000) {
                    throw new AgentProtocolException("RESULT_INVALID",
                            "unresolved 每项必须是 1–1000 字符的文本");
                }
                unresolved.add(item.asText());
            }
        }
        return new AgentSubmission(title, summary, status, markdown, citations, unresolved);
    }

    /**
     * 校验引用并裁剪未使用项。
     *
     * @param sentMaterials 实际送入过模型请求的材料，按 materialId 索引
     */
    public static AgentSubmission validate(AgentSubmission submission,
                                           Map<String, SentMaterial> sentMaterials) {
        Map<String, Citation> byId = new LinkedHashMap<>();
        List<String> citationErrors = new ArrayList<>();
        Set<String> citationHints = new LinkedHashSet<>();
        Set<String> ids = new LinkedHashSet<>();
        for (Citation citation : submission.citations()) {
            boolean validId = citation.id().matches("E[0-9]{1,3}");
            if (!validId) {
                citationErrors.add("引用 ID 必须形如 E1：" + citation.id());
                citationHints.add("citations.id 不带方括号，markdown 中使用对应的 [E1]，不要使用 S1 等编号");
            } else if (!ids.add(citation.id())) {
                citationErrors.add("引用 ID 重复：" + citation.id());
                validId = false;
            }
            if (citation.endLine() < citation.startLine()) {
                citationErrors.add(citation.id() + " 行范围结束早于开始");
                continue;
            }
            SentMaterial material = sentMaterials.get(citation.materialId());
            if (material == null) {
                citationErrors.add(citation.id() + " 指向未实际送入模型的材料："
                        + citation.materialId());
                citationHints.add("请使用本次任务只读工具返回的真实 materialId，不要复制参考结果或其他任务的材料 ID；"
                        + "尚未取得材料时先调用读取工具");
                continue;
            }
            if (material.startLine() == null || material.endLine() == null) {
                citationErrors.add(citation.id() + " 指向没有行范围的材料：" + citation.materialId());
                continue;
            }
            if (citation.startLine() < material.startLine()
                    || citation.endLine() > material.endLine()) {
                citationErrors.add(citation.id() + " 行 " + citation.startLine() + "-"
                        + citation.endLine() + " 超出材料范围 " + material.startLine() + "-"
                        + material.endLine() + "（materialId=" + citation.materialId() + "）");
                citationHints.add("引用必须完全落在对应材料范围内。需要范围外的证据时，先用 read_code 读取所需行，"
                        + "再引用新返回的 materialId；不要直接扩大原引用的行号");
                continue;
            }
            if (validId) {
                byId.put(citation.id(), citation);
            }
        }

        Set<String> referenced = new LinkedHashSet<>();
        Matcher matcher = MARKER.matcher(submission.markdown());
        while (matcher.find()) {
            String id = "E" + matcher.group(1);
            if (!byId.containsKey(id)) {
                citationErrors.add("正文引用了未声明或无效的引用：" + id);
                citationHints.add("请在 citations 中提供与正文标记同名且材料、行范围均有效的引用");
                continue;
            }
            referenced.add(id);
        }
        throwCitationErrors("CITATION_INVALID", citationErrors, citationHints);
        List<Citation> used = new ArrayList<>();
        for (Citation citation : submission.citations()) {
            if (referenced.contains(citation.id())) {
                used.add(citation);
            }
        }
        if (submission.answered() && used.isEmpty()) {
            if (submission.citations().isEmpty()) {
                throw new AgentProtocolException("CITATION_INVALID",
                        "ANSWERED 的 citations 为空。请使用本次任务工具返回的真实 materialId 和材料内行范围，"
                                + "填写至少一项引用，并在 markdown 正文用对应的 [E1] 标记引用它；"
                                + "尚未取得证据时先调用读取工具，不要编造材料或行号");
            }
            throw new AgentProtocolException("CITATION_INVALID",
                    "citations 已提供，但 markdown 正文未使用可识别的引用标记。"
                            + "请使用英文半角方括号和单个编号，如 [E1]、[E2]；（E1）、见 E1、[E1,E2] 不会建立引用。"
                            + "请在正文中引用对应的 citations.id；仅填写 citations 数组不算正文引用");
        }
        if (!submission.answered() && submission.unresolved().isEmpty()) {
            throw new AgentProtocolException("RESULT_INVALID",
                    "INSUFFICIENT_EVIDENCE 结果必须写明未解决事项：unresolved 数组至少填写一项具体缺失材料或无法确认的问题");
        }
        return new AgentSubmission(submission.title(), submission.summary(), submission.answerStatus(),
                submission.markdown(), used, submission.unresolved());
    }

    private static String text(JsonNode root, String field, int maxChars) {
        JsonNode value = root.path(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new AgentProtocolException("RESULT_INVALID", "缺少必填字段：" + field);
        }
        if (value.asText().length() > maxChars) {
            throw new AgentProtocolException("RESULT_INVALID", field + " 超过 " + maxChars + " 字符");
        }
        return value.asText();
    }

    private static String citationText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new AgentProtocolException("RESULT_INVALID", "citation 缺少 " + field);
        }
        return value.asText();
    }

    private static int citationInt(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new AgentProtocolException("RESULT_INVALID", "citation." + field + " 必须是整数");
        }
        return value.intValue();
    }

    private static void throwCitationErrors(String code, List<String> errors, Set<String> hints) {
        if (errors.isEmpty()) {
            return;
        }
        int shown = Math.min(errors.size(), MAX_VALIDATION_DETAILS);
        String message = String.join("；", errors.subList(0, shown));
        if (errors.size() > shown) {
            message += "；另有 " + (errors.size() - shown) + " 项错误未展开";
        }
        if (!hints.isEmpty()) {
            message += "。修正方法：" + String.join("；", hints);
        }
        throw new AgentProtocolException(code, "引用校验发现 " + errors.size() + " 项问题：" + message);
    }
}
