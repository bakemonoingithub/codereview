package com.codereview.agent.tools;

import com.codereview.agent.api.ApiException;
import com.codereview.agent.repository.RepositoryPaths;
import com.codereview.agent.tools.capability.AgentToolCapabilities;
import com.codereview.agent.tools.capability.ToolCapability;
import com.codereview.agent.tools.capability.ToolParameter;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工具参数的严格解析。
 *
 * <p>参数名、必填项、类型与取值范围来自 {@link AgentToolCapabilities} 的同一份能力定义，
 * 模型看到的工具描述也由这份定义生成，schema 与运行时校验不会各写一套约束。
 *
 * <p>不做宽松类型转换、不忽略未知字段、不把越界整数悄悄夹到边界：
 * 参数不合格就返回 {@code INVALID_ARGUMENT}，由模型自己修正。
 * 版本与比较关系只认 {@link AgentToolScope} 里冻结的标识，模型不能直接给 SHA。
 *
 * <p>维护关联 BRC-M04：路径、查询、读取范围和完整性语义由本类、
 * {@link com.codereview.agent.repository.RepositoryPaths} 及各工具实现负责；修改时核对
 * AgentToolCapabilities 的参数说明、tool-usage 模板与 maintenance-map.md。
 * 默认值和数值限额属于第一档，仍只从 ToolParameter、ToolLimits 及冻结配置读取。
 */
public class ToolArgumentParser {

    private final ObjectMapper mapper;

    public ToolArgumentParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public ToolInvocation parse(String tool, String rawArguments, AgentToolScope scope) {
        if (tool == null || tool.isBlank()) {
            throw ToolFailure.invalid("UNKNOWN_TOOL", "缺少工具名");
        }
        ToolCapability capability = AgentToolCapabilities.readOnly(scope.limits()).stream()
                .filter(candidate -> candidate.name().equals(tool))
                .findFirst()
                .orElseThrow(() -> ToolFailure.invalid("UNKNOWN_TOOL", "不支持的工具：" + tool));
        StrictObject args = StrictObject.parse(mapper, rawArguments,
                new LinkedHashSet<>(capability.parameterNames()));
        ParsedArguments parsed = new ParsedArguments();
        for (ToolParameter parameter : capability.parameters()) {
            JsonNode node = parameter.required()
                    ? args.require(parameter.name()) : args.optional(parameter.name());
            read(parameter, node, scope, parsed);
        }
        Map<String, Object> values = parsed.values;
        // 读取范围检查先于未知字段检查，保持改造前的诊断顺序。
        Integer end = readEnd(tool, values);
        args.requireNoUnknown();
        return new ToolInvocation(tool, text(values, "version"), parsed.sha,
                text(values, "comparisonId"), text(values, "path"), (Integer) values.get("depth"),
                limit(tool, values, scope), text(values, "cursor"), text(values, "glob"),
                text(values, "query"), extensions(values), (Integer) values.get("start"), end);
    }

    /** 逐项校验并写入结果；版本与比较关系在这里立即解析，保持原有错误先后顺序。 */
    private static void read(ToolParameter parameter, JsonNode node, AgentToolScope scope,
                             ParsedArguments parsed) {
        if (parameter instanceof ToolParameter.IntegerValue integer) {
            Integer value = integer(node, integer);
            parsed.values.put(parameter.name(), value == null ? integer.parserDefault() : value);
            return;
        }
        if (parameter instanceof ToolParameter.Array array) {
            parsed.values.put(parameter.name(), textArray(node, array));
            return;
        }
        ToolParameter.Text text = (ToolParameter.Text) parameter;
        switch (text.role()) {
            case VERSION_REF -> {
                String reference = text(node, text);
                parsed.sha = requireVersion(scope, reference);
                parsed.values.put(parameter.name(), reference);
            }
            case COMPARISON_REF -> {
                String comparisonId = text(node, text);
                if (scope.comparison(comparisonId) == null) {
                    throw ToolFailure.invalid("UNKNOWN_COMPARISON", "未知比较关系：" + comparisonId);
                }
                parsed.values.put(parameter.name(), comparisonId);
            }
            case PATH -> parsed.values.put(parameter.name(), path(node, text));
            case GLOB -> parsed.values.put(parameter.name(), glob(node, text));
            case QUERY -> parsed.values.put(parameter.name(), query(node, text));
            case CURSOR, PLAIN -> parsed.values.put(parameter.name(), text(node, text));
        }
    }

    private static String requireVersion(AgentToolScope scope, String versionRef) {
        String sha = scope.shaOf(versionRef);
        if (sha == null) {
            throw ToolFailure.invalid("UNKNOWN_VERSION", "未知版本标识：" + versionRef);
        }
        return sha;
    }

    /** 读取类工具没有 limit 参数：单次行数上限由冻结限额决定。 */
    private static int limit(String tool, Map<String, Object> values, AgentToolScope scope) {
        if (AgentToolNames.READ_CODE.equals(tool)) {
            return scope.limits().getReadMaxLines();
        }
        if (AgentToolNames.READ_DIFF.equals(tool)) {
            return scope.limits().getDiffMaxHunks();
        }
        return (Integer) values.get("limit");
    }

    private static Integer readEnd(String tool, Map<String, Object> values) {
        Integer start = (Integer) values.get("start");
        Integer end = (Integer) values.get("end");
        if (AgentToolNames.READ_CODE.equals(tool) && start != null && end != null && end < start) {
            throw ToolFailure.invalid("RANGE_INVALID", "结束行不能小于开始行");
        }
        return end;
    }

    private static List<String> extensions(Map<String, Object> values) {
        Object extensions = values.get("extensions");
        return extensions instanceof List<?> list
                ? list.stream().map(String.class::cast).toList() : List.of();
    }

    private static String text(Map<String, Object> values, String name) {
        return (String) values.get(name);
    }

    private static String text(JsonNode node, ToolParameter.Text spec) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw ToolFailure.invalid("ARGUMENT_TYPE", spec.name() + " 必须是字符串");
        }
        String value = node.asText();
        if (spec.maxChars() != null && value.length() > spec.maxChars()) {
            throw ToolFailure.invalid("ARGUMENT_TOO_LONG", spec.name() + " 超过 " + spec.maxChars() + " 字符");
        }
        return value;
    }

    /** 仓库内相对路径；与读取入口使用同一套校验，避免两处规则分叉。 */
    private static String path(JsonNode node, ToolParameter.Text spec) {
        String value = text(node, spec);
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.startsWith(":")) {
            throw ToolFailure.invalid("ARGUMENT_INVALID", "路径不能以 : 开头：" + trimmed);
        }
        try {
            return RepositoryPaths.requireSafePath(trimmed);
        } catch (ApiException e) {
            throw ToolFailure.invalid("ARGUMENT_INVALID", e.getMessage());
        }
    }

    private static String glob(JsonNode node, ToolParameter.Text spec) {
        String value = text(node, spec);
        if (value == null || value.isBlank()) {
            throw ToolFailure.invalid("ARGUMENT_MISSING", "缺少必填参数：" + spec.name());
        }
        String pattern = value.trim();
        if (pattern.startsWith("/") || pattern.startsWith("\\") || pattern.contains("\\")) {
            throw ToolFailure.invalid("ARGUMENT_INVALID", "glob 只能是仓库内相对模式：" + pattern);
        }
        for (String segment : pattern.split("/")) {
            if ("..".equals(segment) || ".".equals(segment)) {
                throw ToolFailure.invalid("ARGUMENT_INVALID", "glob 不能含 . 或 .. 段：" + pattern);
            }
        }
        return pattern;
    }

    private static String query(JsonNode node, ToolParameter.Text spec) {
        String value = text(node, spec);
        if (value == null) {
            return null;
        }
        if (value.isBlank()) {
            throw ToolFailure.invalid("QUERY_EMPTY", "搜索内容不能为空");
        }
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw ToolFailure.invalid("QUERY_INVALID", "搜索内容只能是单行字面量");
        }
        return value;
    }

    private static Integer integer(JsonNode node, ToolParameter.IntegerValue spec) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isIntegralNumber() || !node.canConvertToInt()) {
            throw ToolFailure.invalid("ARGUMENT_TYPE", spec.name() + " 必须是整数");
        }
        int value = node.intValue();
        if ((spec.minimum() != null && value < spec.minimum())
                || (spec.maximum() != null && value > spec.maximum())) {
            throw ToolFailure.invalid("ARGUMENT_OUT_OF_RANGE",
                    spec.name() + " 必须在 " + spec.minimum() + " 到 " + spec.maximum() + " 之间");
        }
        return value;
    }

    private static List<String> textArray(JsonNode node, ToolParameter.Array spec) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw ToolFailure.invalid("ARGUMENT_TYPE", spec.name() + " 必须是数组");
        }
        if (spec.maxItems() != null && node.size() > spec.maxItems()) {
            throw ToolFailure.invalid("ARGUMENT_TOO_LONG", spec.name() + " 最多 " + spec.maxItems() + " 项");
        }
        ToolParameter.Text item = (ToolParameter.Text) spec.items();
        List<String> values = new ArrayList<>();
        for (JsonNode element : node) {
            if (!element.isTextual()) {
                throw ToolFailure.invalid("ARGUMENT_TYPE", spec.name() + " 必须是字符串数组");
            }
            String value = element.asText();
            if (item.pattern() != null && !value.matches(item.pattern())) {
                throw ToolFailure.invalid("ARGUMENT_INVALID", spec.name() + " 含非法值：" + value);
            }
            values.add(value);
        }
        return List.copyOf(values);
    }

    /** 一次解析的中间结果：按参数名保存值，版本标识额外记录解析出的 SHA。 */
    private static final class ParsedArguments {

        private final Map<String, Object> values = new LinkedHashMap<>();
        private String sha;
    }

    /** 只接受 JSON 对象、单值、无尾随内容；未知字段显式拒绝。 */
    private static final class StrictObject {

        private final JsonNode node;
        private final Set<String> allowed;

        private StrictObject(JsonNode node, Set<String> allowed) {
            this.node = node;
            this.allowed = allowed;
        }

        static StrictObject parse(ObjectMapper mapper, String raw, Set<String> allowed) {
            if (raw == null || raw.isBlank()) {
                throw ToolFailure.invalid("ARGUMENTS_MISSING", "缺少工具参数");
            }
            JsonNode root;
            try (JsonParser parser = mapper.getFactory().createParser(raw)) {
                root = mapper.readTree(parser);
                if (root == null) {
                    throw ToolFailure.invalid("ARGUMENTS_INVALID", "工具参数不是 JSON 值");
                }
                if (parser.nextToken() != null) {
                    throw ToolFailure.invalid("ARGUMENTS_INVALID", "工具参数之后存在多余内容");
                }
            } catch (IOException e) {
                throw ToolFailure.invalid("ARGUMENTS_INVALID", "工具参数不是合法 JSON");
            }
            if (!root.isObject()) {
                throw ToolFailure.invalid("ARGUMENTS_INVALID", "工具参数必须是 JSON 对象");
            }
            return new StrictObject(root, allowed);
        }

        JsonNode require(String field) {
            checkKnown(field);
            JsonNode value = node.get(field);
            if (value == null || value.isNull()) {
                throw ToolFailure.invalid("ARGUMENT_MISSING", "缺少必填参数：" + field);
            }
            return value;
        }

        JsonNode optional(String field) {
            checkKnown(field);
            return node.get(field);
        }

        private void checkKnown(String field) {
            if (!allowed.contains(field)) {
                throw ToolFailure.invalid("ARGUMENT_UNKNOWN", "不认识的参数：" + field);
            }
        }

        /** 解析结束后检查未声明字段；每条工具分支末尾显式调用。 */
        void requireNoUnknown() {
            node.fieldNames().forEachRemaining(name -> {
                if (!allowed.contains(name)) {
                    throw ToolFailure.invalid("ARGUMENT_UNKNOWN", "不认识的参数：" + name);
                }
            });
        }
    }
}
