package com.codereview.agent.tools;

import com.codereview.agent.api.ApiException;
import com.codereview.agent.repository.RepositoryPaths;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 工具参数的严格解析。
 *
 * <p>不做宽松类型转换、不忽略未知字段、不把越界整数悄悄夹到边界：
 * 参数不合格就返回 {@code INVALID_ARGUMENT}，由模型自己修正。
 * 版本与比较关系只认 {@link AgentToolScope} 里冻结的标识，模型不能直接给 SHA。
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
        if (!AgentToolNames.isReadOnly(tool)) {
            throw ToolFailure.invalid("UNKNOWN_TOOL", "不支持的工具：" + tool);
        }
        StrictObject args = StrictObject.parse(mapper, rawArguments);
        return switch (tool) {
            case AgentToolNames.TREE -> parseTree(args, scope);
            case AgentToolNames.FIND_FILES -> parseFind(args, scope);
            case AgentToolNames.SEARCH_CODE -> parseSearch(args, scope);
            case AgentToolNames.READ_CODE -> parseRead(args, scope);
            case AgentToolNames.READ_DIFF -> parseDiff(args, scope);
            default -> throw ToolFailure.invalid("UNKNOWN_TOOL", "不支持的工具：" + tool);
        };
    }

    private ToolInvocation parseTree(StrictObject args, AgentToolScope scope) {
        args.allow("version", "path", "depth", "limit", "cursor");
        String versionRef = args.require("version").text(16);
        String sha = requireVersion(scope, versionRef);
        String path = args.optional("path").path();
        int depth = args.optional("depth").integer(ToolLimits.TREE_DEFAULT_DEPTH, 1, ToolLimits.TREE_MAX_DEPTH);
        int limit = args.optional("limit").integer(ToolLimits.TREE_DEFAULT_LIMIT, 1, ToolLimits.TREE_MAX_LIMIT);
        String cursor = args.optional("cursor").text(ToolLimits.CURSOR_MAX_CHARS);
        args.requireNoUnknown();
        return new ToolInvocation(AgentToolNames.TREE, versionRef, sha, null, path, depth, limit,
                cursor, null, null, List.of(), null, null);
    }

    private ToolInvocation parseFind(StrictObject args, AgentToolScope scope) {
        args.allow("version", "glob", "path", "limit", "cursor");
        String versionRef = args.require("version").text(16);
        String sha = requireVersion(scope, versionRef);
        String glob = args.require("glob").glob();
        String path = args.optional("path").path();
        int limit = args.optional("limit").integer(ToolLimits.FIND_DEFAULT_LIMIT, 1, ToolLimits.FIND_MAX_LIMIT);
        String cursor = args.optional("cursor").text(ToolLimits.CURSOR_MAX_CHARS);
        args.requireNoUnknown();
        return new ToolInvocation(AgentToolNames.FIND_FILES, versionRef, sha, null, path, null, limit,
                cursor, glob, null, List.of(), null, null);
    }

    private ToolInvocation parseSearch(StrictObject args, AgentToolScope scope) {
        args.allow("version", "query", "path", "extensions", "limit", "cursor");
        String versionRef = args.require("version").text(16);
        String sha = requireVersion(scope, versionRef);
        String query = args.require("query").text(ToolLimits.SEARCH_QUERY_MAX_CHARS);
        if (query.isBlank()) {
            throw ToolFailure.invalid("QUERY_EMPTY", "搜索内容不能为空");
        }
        if (query.indexOf('\n') >= 0 || query.indexOf('\r') >= 0) {
            throw ToolFailure.invalid("QUERY_INVALID", "搜索内容只能是单行字面量");
        }
        String path = args.optional("path").path();
        List<String> extensions = args.optional("extensions").textArray(
                ToolLimits.SEARCH_MAX_EXTENSIONS, "^[.A-Za-z0-9_+-]{1,20}$");
        int limit = args.optional("limit").integer(ToolLimits.SEARCH_DEFAULT_LIMIT, 1, ToolLimits.SEARCH_MAX_LIMIT);
        String cursor = args.optional("cursor").text(ToolLimits.CURSOR_MAX_CHARS);
        args.requireNoUnknown();
        return new ToolInvocation(AgentToolNames.SEARCH_CODE, versionRef, sha, null, path, null, limit,
                cursor, null, query, extensions, null, null);
    }

    private ToolInvocation parseRead(StrictObject args, AgentToolScope scope) {
        args.allow("version", "path", "start", "end");
        String versionRef = args.require("version").text(16);
        String sha = requireVersion(scope, versionRef);
        String path = args.require("path").path();
        Integer start = args.optional("start").integerOrNull(1, 10_000_000);
        Integer end = args.optional("end").integerOrNull(1, 10_000_000);
        if (start != null && end != null && end < start) {
            throw ToolFailure.invalid("RANGE_INVALID", "结束行不能小于开始行");
        }
        args.requireNoUnknown();
        return new ToolInvocation(AgentToolNames.READ_CODE, versionRef, sha, null, path, null,
                ToolLimits.READ_MAX_LINES, null, null, null, List.of(), start, end);
    }

    private ToolInvocation parseDiff(StrictObject args, AgentToolScope scope) {
        args.allow("comparisonId", "path", "cursor");
        String comparisonId = args.require("comparisonId").text(16);
        if (scope.comparison(comparisonId) == null) {
            throw ToolFailure.invalid("UNKNOWN_COMPARISON", "未知比较关系：" + comparisonId);
        }
        String path = args.optional("path").path();
        String cursor = args.optional("cursor").text(ToolLimits.CURSOR_MAX_CHARS);
        args.requireNoUnknown();
        return new ToolInvocation(AgentToolNames.READ_DIFF, null, null, comparisonId, path, null,
                ToolLimits.DIFF_MAX_HUNKS, cursor, null, null, List.of(), null, null);
    }

    private static String requireVersion(AgentToolScope scope, String versionRef) {
        String sha = scope.shaOf(versionRef);
        if (sha == null) {
            throw ToolFailure.invalid("UNKNOWN_VERSION", "未知版本标识：" + versionRef);
        }
        return sha;
    }

    /** 只接受 JSON 对象、单值、无尾随内容；未知字段显式拒绝。 */
    private static final class StrictObject {

        private final JsonNode node;
        private final Set<String> allowed = new LinkedHashSet<>();

        private StrictObject(JsonNode node) {
            this.node = node;
        }

        static StrictObject parse(ObjectMapper mapper, String raw) {
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
            return new StrictObject(root);
        }

        void allow(String... fields) {
            for (String field : fields) {
                allowed.add(field);
            }
        }

        Field require(String field) {
            checkKnown(field);
            JsonNode value = node.get(field);
            if (value == null || value.isNull()) {
                throw ToolFailure.invalid("ARGUMENT_MISSING", "缺少必填参数：" + field);
            }
            return new Field(field, value);
        }

        Field optional(String field) {
            checkKnown(field);
            return new Field(field, node.get(field));
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

        private final class Field {

            private final String name;
            private final JsonNode node;

            private Field(String name, JsonNode node) {
                this.name = name;
                this.node = node;
            }

            String text(int maxChars) {
                if (node == null || node.isNull()) {
                    return null;
                }
                if (!node.isTextual()) {
                    throw typeError("字符串");
                }
                String value = node.asText();
                if (value.length() > maxChars) {
                    throw ToolFailure.invalid("ARGUMENT_TOO_LONG", name + " 超过 " + maxChars + " 字符");
                }
                return value;
            }

            int integer(int defaultValue, int min, int max) {
                Integer value = integerOrNull(min, max);
                return value == null ? defaultValue : value;
            }

            Integer integerOrNull(int min, int max) {
                if (node == null || node.isNull()) {
                    return null;
                }
                if (!node.isIntegralNumber() || !node.canConvertToInt()) {
                    throw typeError("整数");
                }
                int value = node.intValue();
                if (value < min || value > max) {
                    throw ToolFailure.invalid("ARGUMENT_OUT_OF_RANGE",
                            name + " 必须在 " + min + " 到 " + max + " 之间");
                }
                return value;
            }

            /** 仓库内相对路径；与读取入口使用同一套校验，避免两处规则分叉。 */
            String path() {
                String value = text(ToolLimits.PATH_MAX_CHARS);
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

            String glob() {
                String value = text(ToolLimits.GLOB_MAX_CHARS);
                if (value == null || value.isBlank()) {
                    throw ToolFailure.invalid("ARGUMENT_MISSING", "缺少必填参数：" + name);
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

            List<String> textArray(int maxItems, String itemPattern) {
                if (node == null || node.isNull()) {
                    return List.of();
                }
                if (!node.isArray()) {
                    throw typeError("数组");
                }
                if (node.size() > maxItems) {
                    throw ToolFailure.invalid("ARGUMENT_TOO_LONG", name + " 最多 " + maxItems + " 项");
                }
                List<String> values = new ArrayList<>();
                for (JsonNode item : node) {
                    if (!item.isTextual()) {
                        throw typeError("字符串数组");
                    }
                    String value = item.asText();
                    if (!value.matches(itemPattern)) {
                        throw ToolFailure.invalid("ARGUMENT_INVALID", name + " 含非法值：" + value);
                    }
                    values.add(value);
                }
                return List.copyOf(values);
            }

            private ToolFailure typeError(String expected) {
                return ToolFailure.invalid("ARGUMENT_TYPE", name + " 必须是" + expected);
            }
        }
    }
}
