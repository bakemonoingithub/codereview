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

    private AgentToolSchema() {
    }

    public static String version() {
        return AgentToolNames.SCHEMA_VERSION;
    }

    public static List<Map<String, Object>> definitions() {
        List<Map<String, Object>> tools = new ArrayList<>();
        tools.add(function(AgentToolNames.TREE,
                "列出固定版本下某一目录的直接条目（路径、类型、下级计数）。"
                        + "目录深度是请求范围，不代表已递归读遍所有文件。",
                params(Map.of(
                                "version", opaqueVersion("固定版本标识，只能使用作用域给出的 V1…Vm"),
                                "path", relativePath("仓库内目录，默认仓库根"),
                                "depth", integer("展开层数，默认 2", ToolLimits.TREE_DEFAULT_DEPTH,
                                        1, ToolLimits.TREE_MAX_DEPTH),
                                "limit", integer("最多返回条目数，默认 100", ToolLimits.TREE_DEFAULT_LIMIT,
                                        1, ToolLimits.TREE_MAX_LIMIT),
                                "cursor", cursor()),
                        List.of("version"))));
        tools.add(function(AgentToolNames.FIND_FILES,
                "在固定版本中按 glob 匹配文件路径。支持 *、**、?，不做 shell 花括号展开，大小写按仓库实际路径。",
                params(Map.of(
                                "version", opaqueVersion("固定版本标识"),
                                "glob", Map.of("type", "string", "maxLength", ToolLimits.GLOB_MAX_CHARS,
                                        "description", "仓库内相对 glob，例如 src/**/*.java"),
                                "path", relativePath("限定目录前缀，可选"),
                                "limit", integer("最多返回路径数，默认 50", ToolLimits.FIND_DEFAULT_LIMIT,
                                        1, ToolLimits.FIND_MAX_LIMIT),
                                "cursor", cursor()),
                        List.of("version", "glob"))));
        tools.add(function(AgentToolNames.SEARCH_CODE,
                "在固定版本中按字面量搜索代码（不解释正则），返回路径、原始行号、命中行及前后各 2 行。"
                        + "每个片段有独立材料 ID；零命中只有在完整性为 COMPLETE 时才说明确实不存在。",
                params(Map.of(
                                "version", opaqueVersion("固定版本标识"),
                                "query", Map.of("type", "string", "minLength", 1,
                                        "maxLength", ToolLimits.SEARCH_QUERY_MAX_CHARS,
                                        "description", "字面量搜索内容，最多 256 字符"),
                                "path", relativePath("限定目录前缀，可选"),
                                "extensions", Map.of("type", "array", "maxItems",
                                        ToolLimits.SEARCH_MAX_EXTENSIONS,
                                        "items", Map.of("type", "string",
                                                "pattern", "^[.A-Za-z0-9_+-]{1,20}$"),
                                        "description", "至多 10 个后缀过滤，例如 .java"),
                                "limit", integer("最多返回命中数，默认 50", ToolLimits.SEARCH_DEFAULT_LIMIT,
                                        1, ToolLimits.SEARCH_MAX_LIMIT),
                                "cursor", cursor()),
                        List.of("version", "query"))));
        tools.add(function(AgentToolNames.READ_CODE,
                "按原始行号读取固定版本的一段文件内容，返回内容哈希、总行数与下一页起点。"
                        + "一次最多 400 行且正文最多 24 KiB；从实际返回的末行+1 继续读。",
                params(Map.of(
                                "version", opaqueVersion("固定版本标识"),
                                "path", relativePath("仓库内文件路径"),
                                "start", integer("起始行，默认 1", 1, 1, 10_000_000),
                                "end", integer("结束行，默认 start+199", 1, 1, 10_000_000)),
                        List.of("version", "path"))));
        tools.add(function(AgentToolNames.READ_DIFF,
                "读取一个冻结比较关系的变更清单，或指定路径的逐 hunk 前后侧内容。"
                        + "一次最多 20 个变更项或 10 个 hunk；分页不合并其他提交，前后侧均可引用。",
                params(Map.of(
                                "comparisonId", opaqueVersion("比较关系标识，只能使用 C1…Cn"),
                                "path", relativePath("规范新路径；省略时返回变更清单"),
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
    public static List<Map<String, Object>> agentTools() {
        List<Map<String, Object>> tools = new ArrayList<>(definitions());
        tools.add(submitResult());
        return List.copyOf(tools);
    }

    private static Map<String, Object> submitResult() {
        Map<String, Object> citation = new LinkedHashMap<>();
        citation.put("type", "object");
        citation.put("additionalProperties", false);
        citation.put("required", List.of("id", "materialId", "startLine", "endLine"));
        Map<String, Object> citationProperties = new LinkedHashMap<>();
        citationProperties.put("id", Map.of("type", "string", "pattern", "^E[0-9]{1,3}$"));
        citationProperties.put("materialId", Map.of("type", "string", "maxLength", 26));
        citationProperties.put("startLine", Map.of("type", "integer", "minimum", 1));
        citationProperties.put("endLine", Map.of("type", "integer", "minimum", 1));
        citation.put("properties", citationProperties);

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("title", Map.of("type", "string", "maxLength", 200));
        properties.put("summary", Map.of("type", "string", "maxLength", 1000));
        properties.put("answerStatus", Map.of("type", "string",
                "enum", List.of(AgentSubmission.ANSWERED, AgentSubmission.INSUFFICIENT_EVIDENCE)));
        properties.put("markdown", Map.of("type", "string", "maxLength", 24000));
        properties.put("citations", Map.of("type", "array", "maxItems", 100, "items", citation));
        properties.put("unresolved", Map.of("type", "array", "maxItems", 20,
                "items", Map.of("type", "string", "maxLength", 1000)));

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("additionalProperties", false);
        parameters.put("properties", properties);
        parameters.put("required", List.of("title", "summary", "answerStatus", "markdown"));
        return function(AgentToolNames.SUBMIT_RESULT,
                "结束调查并提交结果，必须单独调用，不能与其他工具同批。"
                        + "引用只能使用实际返回材料的 materialId 与原始行号；"
                        + "ANSWERED 至少一个引用，INSUFFICIENT_EVIDENCE 必须写明未解决事项。",
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
        return Map.of("type", "string", "maxLength", ToolLimits.PATH_MAX_CHARS, "description", description);
    }

    private static Map<String, Object> integer(String description, int defaultValue, int min, int max) {
        return Map.of("type", "integer", "minimum", min, "maximum", max,
                "default", defaultValue, "description", description);
    }

    private static Map<String, Object> cursor() {
        return Map.of("type", "string", "maxLength", ToolLimits.CURSOR_MAX_CHARS,
                "description", "上一页返回的 nextCursor；只对同一查询有效");
    }
}
