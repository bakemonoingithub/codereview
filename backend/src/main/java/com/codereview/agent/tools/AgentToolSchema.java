package com.codereview.agent.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 五类只读工具的 JSON Schema 定义。
 *
 * <p>schema 是模型协议的公开面：字段名、类型、默认值和上限只在这里维护；
 * 参数校验在 {@link ToolArgumentParser} 按同一组上限执行，二者不一致视为缺陷。
 * {@code additionalProperties=false} 明确告诉模型不要发明字段。
 */
public final class AgentToolSchema {

    /** 仅演示提交结构；调用方必须替换材料 ID 和行号。 */
    static final String ANSWERED_EXAMPLE = """
            {"title":"定位结果","summary":"结论由已读取材料支持。","answerStatus":"ANSWERED",
             "markdown":"结论由已读取材料支持。[E1]",
             "citations":[{"id":"E1","materialId":"<实际materialId>","startLine":12,"endLine":13}],
             "unresolved":[]}
            """;
    static final String INSUFFICIENT_EVIDENCE_EXAMPLE = """
            {"title":"材料不足","summary":"尚缺关键实现材料。","answerStatus":"INSUFFICIENT_EVIDENCE",
             "markdown":"未取得关键实现，无法确认结论。","citations":[],
             "unresolved":["尚未读取到关键实现。"]}
            """;

    private AgentToolSchema() {
    }

    public static String version() {
        return version(new ToolLimitProperties());
    }

    public static List<Map<String, Object>> definitions() { return definitions(new ToolLimitProperties()); }
    public static String version(ToolLimitProperties limits) {
        try {
            String json = new com.fasterxml.jackson.databind.ObjectMapper()
                    .configure(com.fasterxml.jackson.databind.MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                    .writeValueAsString(limits);
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return "agent-tools-v2-" + java.util.HexFormat.of().formatHex(digest).substring(0, 24);
        } catch (Exception e) { throw new IllegalStateException("工具配置指纹失败", e); }
    }
    public static List<Map<String, Object>> definitions(ToolLimitProperties limits) {
        List<Map<String, Object>> tools = new ArrayList<>();
        tools.add(function(AgentToolNames.TREE,
                "列出固定版本下某一目录的直接条目（路径、类型、下级计数）。"
                        + "目录深度是请求范围，不代表已递归读遍所有文件。"
                        + "列仓库根目录时必须省略 path，例如 {\"version\":\"V1\"}；"
                        + "列子目录例如 {\"version\":\"V1\",\"path\":\"backend/src\"}。",
                params(Map.of(
                                "version", opaqueVersion("固定版本标识，只能使用作用域给出的 V1…Vm"),
                                "path", relativePath("可选仓库内目录；省略 path 表示根目录，不要传空字符串或 \".\"。"),
                                "depth", integer("展开层数，默认 " + limits.getTreeDefaultDepth(), limits.getTreeDefaultDepth(),
                                        1, limits.getTreeMaxDepth()),
                                "limit", integer("最多返回条目数，默认 " + limits.getTreeDefaultLimit(), limits.getTreeDefaultLimit(),
                                        1, limits.getTreeMaxLimit()),
                                "cursor", cursor()),
                        List.of("version"))));
        tools.add(function(AgentToolNames.FIND_FILES,
                "在固定版本中按 glob 匹配文件路径。支持 *、**、?，不做 shell 花括号展开，大小写按仓库实际路径。",
                params(Map.of(
                                "version", opaqueVersion("固定版本标识"),
                                "glob", Map.of("type", "string", "maxLength", ToolLimits.GLOB_MAX_CHARS,
                                        "description", "仓库内相对 glob，例如 src/**/*.java"),
                                "path", relativePath("可选目录前缀；查找全仓库时省略 path，不要传空字符串或 \".\"。"),
                                "limit", integer("最多返回路径数，默认 " + limits.getFindDefaultLimit(), limits.getFindDefaultLimit(),
                                        1, limits.getFindMaxLimit()),
                                "cursor", cursor()),
                        List.of("version", "glob"))));
        tools.add(function(AgentToolNames.SEARCH_CODE,
                "在固定版本中按字面量搜索代码（不解释正则），返回路径、原始行号、命中行及前后各 " + limits.getSearchContextLines() + " 行。"
                        + "每个片段有独立材料 ID；零命中只有在完整性为 COMPLETE 时才说明确实不存在。",
                params(Map.of(
                                "version", opaqueVersion("固定版本标识"),
                                "query", Map.of("type", "string", "minLength", 1,
                                        "maxLength", ToolLimits.SEARCH_QUERY_MAX_CHARS,
                                        "description", "非空、单行字面量搜索内容，不接受换行或回车，最多 256 字符"),
                                "path", relativePath("可选路径前缀；搜索全仓库时省略 path，不要传空字符串或 \".\"。"),
                                "extensions", Map.of("type", "array", "maxItems",
                                        ToolLimits.SEARCH_MAX_EXTENSIONS,
                                        "items", Map.of("type", "string",
                                                "pattern", "^[.A-Za-z0-9_+-]{1,20}$"),
                                        "description", "至多 10 个后缀过滤，例如 .java"),
                                "limit", integer("最多返回命中数，默认 " + limits.getSearchDefaultLimit(), limits.getSearchDefaultLimit(),
                                        1, limits.getSearchMaxLimit()),
                                "cursor", cursor()),
                        List.of("version", "query"))));
        tools.add(function(AgentToolNames.READ_CODE,
                "按原始行号读取固定版本的一段文件内容，返回内容哈希、总行数与下一页起点。"
                        + "一次最多 " + limits.getReadMaxLines() + " 行且正文最多 " + (limits.getMaterialMaxBytes() % 1024 == 0 ? (limits.getMaterialMaxBytes() / 1024) + " KiB" : limits.getMaterialMaxBytes() + " 字节") + "；从实际返回的末行+1 继续读。",
                params(Map.of(
                                "version", opaqueVersion("固定版本标识"),
                                "path", relativePath("必填的具体文件路径，不能省略或传空字符串、\".\"。"),
                                "start", integer("起始行，默认 1", 1, 1, 10_000_000),
                                "end", Map.of("type", "integer", "minimum", 1, "maximum", 10_000_000,
                                        "description", "包含在读取范围内的结束行，不能小于起始行；省略时为 start+" + (limits.getReadDefaultLines() - 1))),
                        List.of("version", "path"))));
        tools.add(function(AgentToolNames.READ_DIFF,
                "读取一个冻结比较关系的变更清单，或指定路径的逐 hunk 前后侧内容。"
                        + "一次最多 " + limits.getDiffMaxItems() + " 个变更项或 " + limits.getDiffMaxHunks() + " 个 hunk；分页不合并其他提交，前后侧均可引用。",
                params(Map.of(
                                "comparisonId", opaqueVersion("比较关系标识，只能使用 C1…Cn"),
                                "path", relativePath("可选的变更清单中的规范新路径；省略 path 返回变更清单，不要传空字符串或 \".\"；切换文件时省略 cursor。"),
                                "cursor", cursor()),
                        List.of("comparisonId"))));
        return List.copyOf(tools);
    }

    /**
     * Agent 执行使用的工具清单：五类只读工具 + 显式提交动作。
     *
     * <p>{@code submit_result} 只在执行循环里暴露，不进入只读工具参数解析；
     * 它与其他工具同批出现时整批不执行。
     */
    public static List<Map<String, Object>> agentTools() { return agentTools(new ToolLimitProperties()); }
    public static List<Map<String, Object>> agentTools(ToolLimitProperties limits) {
        List<Map<String, Object>> tools = new ArrayList<>(definitions(limits));
        tools.add(submitResult());
        return List.copyOf(tools);
    }

    private static Map<String, Object> submitResult() {
        Map<String, Object> citation = new LinkedHashMap<>();
        citation.put("type", "object");
        citation.put("additionalProperties", false);
        citation.put("required", List.of("id", "materialId", "startLine", "endLine"));
        Map<String, Object> citationProperties = new LinkedHashMap<>();
        citationProperties.put("id", Map.of("type", "string", "pattern", "^E[0-9]{1,3}$",
                "description", "本次结果内唯一的引用编号，如 E1、E2，不带方括号；正文中写 [E1]、[E2]，不要使用 S1。"));
        citationProperties.put("materialId", Map.of("type", "string", "maxLength", 26,
                "description", "原样填写本次任务只读工具返回并已送入模型的材料 ID；不能编造，也不能从参考结果或其他任务复制。"));
        citationProperties.put("startLine", Map.of("type", "integer", "minimum", 1,
                "description", "引用起始行，包含端点，必须位于该 materialId 的实际返回行范围内。"));
        citationProperties.put("endLine", Map.of("type", "integer", "minimum", 1,
                "description", "引用结束行，包含端点，不能小于 startLine，也不能超出该材料末行。"
                        + "不能把同一文件的多份材料范围拼到一个 materialId 上；需要更大范围时先调用 read_code。"));
        citation.put("properties", citationProperties);

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("title", Map.of("type", "string", "maxLength", 200));
        properties.put("summary", Map.of("type", "string", "maxLength", 1000));
        properties.put("answerStatus", Map.of("type", "string",
                "enum", List.of(AgentSubmission.ANSWERED, AgentSubmission.INSUFFICIENT_EVIDENCE),
                "description", "ANSWERED 要求至少一项有效引用且正文实际使用；INSUFFICIENT_EVIDENCE 要求 unresolved 非空，可无引用。"));
        properties.put("markdown", Map.of("type", "string", "maxLength", 24000,
                "description", "结果正文；引用写在正文中，必须使用英文半角方括号，如：结论。[E1]。"
                        + "多项写作 [E1][E2]，不能写成（E1）、见 E1 或 [E1,E2]；每个标记必须对应 citations 中有效的同名 id。"));
        properties.put("citations", Map.of("type", "array", "maxItems", 100, "items", citation,
                "description", "引用定义；ANSWERED 必须提供至少一项有效定义，并在 markdown 中以 [E1] 等标记实际引用。"
                        + "只填数组不算正文引用；正文未使用的定义不会发布。"));
        properties.put("unresolved", Map.of("type", "array", "maxItems", 20,
                "items", Map.of("type", "string", "maxLength", 1000),
                "description", "具体缺失材料或无法确认的问题；INSUFFICIENT_EVIDENCE 至少填写一项非空文本，无未解决事项时填空数组。"));

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("additionalProperties", false);
        parameters.put("properties", properties);
        parameters.put("required", List.of("title", "summary", "answerStatus", "markdown"));
        return function(AgentToolNames.SUBMIT_RESULT,
                "结束调查并提交结果，必须单独调用，不能与其他工具同批。"
                        + "citations.id 使用 E1、E2，markdown 中必须写成 [E1]、[E2]，不能只填写引用数组。"
                        + "引用必须来自本次任务实际返回且已送入模型的材料，行号不得超出该 materialId 的返回范围。"
                        + "ANSWERED 至少一个正文实际使用的有效引用；INSUFFICIENT_EVIDENCE 必须填写非空 unresolved。"
                        + "以下示例仅演示格式，materialId 占位符和行号必须替换为本次实际材料值，不能照抄："
                        + ANSWERED_EXAMPLE
                        + "未取得证据时的提交示例：" + INSUFFICIENT_EVIDENCE_EXAMPLE,
                parameters);
    }

    private static Map<String, Object> function(String name, String description,
                                                Map<String, Object> parameters) {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", name);
        function.put("description", description);
        function.put("parameters", parameters);
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("type", "function");
        tool.put("function", function);
        return tool;
    }

    private static Map<String, Object> params(Map<String, Object> properties, List<String> required) {
        Map<String, Object> sorted = new LinkedHashMap<>();
        for (String field : List.of("version", "comparisonId", "query", "glob", "path", "extensions",
                "depth", "limit", "start", "end", "cursor")) {
            if (properties.containsKey(field)) {
                sorted.put(field, properties.get(field));
            }
        }
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("additionalProperties", false);
        parameters.put("properties", sorted);
        parameters.put("required", required);
        return parameters;
    }

    private static Map<String, Object> opaqueVersion(String description) {
        return Map.of("type", "string", "pattern", "^[A-Za-z0-9_-]{1,16}$", "description", description);
    }

    private static Map<String, Object> relativePath(String description) {
        return Map.of("type", "string", "maxLength", ToolLimits.PATH_MAX_CHARS, "description", description
                + "提供路径时必须为非空仓库内相对路径，使用 / 分隔；禁止绝对路径、反斜杠以及 . 或 .. 路径段。");
    }

    private static Map<String, Object> integer(String description, int defaultValue, int min, int max) {
        return Map.of("type", "integer", "minimum", min, "maximum", max,
                "default", defaultValue, "description", description);
    }

    private static Map<String, Object> cursor() {
        return Map.of("type", "string", "maxLength", ToolLimits.CURSOR_MAX_CHARS,
                "description", "第一页省略 cursor；翻页只原样使用上一页返回的 nextCursor，其他查询参数保持一致，"
                        + "包括本工具使用的 version/comparisonId、path、depth、limit、glob、query、extensions。"
                        + "改变参数或切换清单／文件时，省略 cursor 从第一页重新开始；不要自行构造游标。");
    }
}
