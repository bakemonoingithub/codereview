package com.codereview.agent.prompt.template;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 内置提示词模板的加载与渲染。
 *
 * <p>模板正文随应用发布，位于 classpath 的 {@code prompts/} 下；{@code prompts/manifest.json}
 * 声明标识、类别、用途、路径、版本与允许变量。加载即校验：清单缺失、版本不支持、标识重复、
 * 资源缺失或为空、正文占位符与声明变量不一致，都直接失败，不回退为空提示词，也不做部分加载。
 *
 * <p>渲染只做一次明确替换：变量值中的 {@code {{...}}} 不会被再次解释，因此材料或用户输入
 * 不会因为包含占位符而被二次展开。正文中的空白与末尾换行原样保留，加载与渲染都不 strip。
 */
public final class PromptTemplateRegistry {

    /** 模板清单位置（classpath 相对路径）。 */
    public static final String MANIFEST_PATH = "prompts/manifest.json";
    /** 当前支持的清单结构版本。 */
    public static final int SCHEMA_VERSION = 1;

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([A-Za-z0-9_.-]+)}}");
    private static final String PLACEHOLDER_OPEN = "{{";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, PromptTemplate> templates;

    private PromptTemplateRegistry(Map<String, PromptTemplate> templates) {
        this.templates = Collections.unmodifiableMap(new LinkedHashMap<>(templates));
    }

    /** 从当前线程上下文（或本类）classpath 加载随应用发布的模板。 */
    public static PromptTemplateRegistry loadDefault() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return load(loader == null ? PromptTemplateRegistry.class.getClassLoader() : loader);
    }

    /** 从指定 classpath 加载；资源必须存在于构建产物中，而不是开发机绝对路径。 */
    public static PromptTemplateRegistry load(ClassLoader loader) {
        Objects.requireNonNull(loader, "loader");
        try (InputStream stream = loader.getResourceAsStream(MANIFEST_PATH)) {
            if (stream == null) {
                throw new IllegalStateException("缺少提示词模板清单：" + MANIFEST_PATH);
            }
            return load(new InputStreamReader(stream, StandardCharsets.UTF_8),
                    path -> readResource(loader, path));
        } catch (IOException e) {
            throw new UncheckedIOException("读取提示词模板清单失败：" + MANIFEST_PATH, e);
        }
    }

    /**
     * 从任意清单来源加载，供测试构造缺失资源、未声明变量等场景。
     *
     * @param manifest  清单 JSON
     * @param resources 资源读取器；路径不存在时返回 {@code null}
     */
    static PromptTemplateRegistry load(Reader manifest, Function<String, String> resources) {
        JsonNode root;
        try {
            root = MAPPER.readTree(manifest);
        } catch (IOException e) {
            throw new UncheckedIOException("解析提示词模板清单失败", e);
        }
        int schemaVersion = root == null || root.isNull() ? -1 : root.path("schemaVersion").asInt(-1);
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalStateException("提示词模板清单结构版本不支持：" + schemaVersion);
        }
        JsonNode entries = root.path("templates");
        if (!entries.isArray() || entries.isEmpty()) {
            throw new IllegalStateException("提示词模板清单为空：" + MANIFEST_PATH);
        }
        Map<String, PromptTemplate> loaded = new LinkedHashMap<>();
        for (JsonNode entry : entries) {
            PromptTemplate template = loadEntry(entry, resources);
            if (loaded.putIfAbsent(template.id(), template) != null) {
                throw new IllegalStateException("提示词模板标识重复：" + template.id());
            }
        }
        return new PromptTemplateRegistry(loaded);
    }

    /** 按标识取模板；标识不存在属于编程错误。 */
    public PromptTemplate require(String id) {
        PromptTemplate template = templates.get(id);
        if (template == null) {
            throw new IllegalArgumentException("未知提示词模板：" + id);
        }
        return template;
    }

    /** 已加载的全部模板标识，按清单顺序。 */
    public Set<String> ids() {
        return templates.keySet();
    }

    /**
     * 渲染模板正文。
     *
     * <p>传入变量必须与清单声明完全一致：缺一个即报缺少变量，多一个即报未知变量。
     * 只扫描模板一次，变量值原样插入，不递归渲染。
     */
    public String render(String id, Map<String, String> values) {
        PromptTemplate template = require(id);
        Map<String, String> provided = values == null ? Map.of() : values;
        Set<String> missing = new LinkedHashSet<>(template.variables());
        missing.removeAll(provided.keySet());
        Set<String> unknown = new LinkedHashSet<>(provided.keySet());
        unknown.removeAll(template.variables());
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("提示词模板 " + id + " 缺少变量：" + missing);
        }
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("提示词模板 " + id + " 未知变量：" + unknown);
        }
        Matcher matcher = PLACEHOLDER.matcher(template.body());
        StringBuilder rendered = new StringBuilder(template.body().length());
        while (matcher.find()) {
            String value = provided.get(matcher.group(1));
            if (value == null) {
                throw new IllegalStateException("提示词模板 " + id + " 变量无值：" + matcher.group(1));
            }
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }

    private static PromptTemplate loadEntry(JsonNode entry, Function<String, String> resources) {
        String id = requiredText(entry, "id");
        String kind = requiredText(entry, "kind");
        String version = requiredText(entry, "version");
        String path = requiredText(entry, "path");
        String purpose = entry.path("purpose").isTextual() ? entry.path("purpose").asText() : null;
        Set<String> declared = new LinkedHashSet<>();
        JsonNode variables = entry.path("variables");
        if (!variables.isMissingNode() && !variables.isNull()) {
            if (!variables.isArray()) {
                throw new IllegalStateException("提示词模板 " + id + " 的 variables 必须是数组");
            }
            for (JsonNode variable : variables) {
                if (!variable.isTextual() || variable.asText().isBlank()) {
                    throw new IllegalStateException("提示词模板 " + id + " 声明了空变量名");
                }
                if (!declared.add(variable.asText())) {
                    throw new IllegalStateException("提示词模板 " + id + " 重复声明变量：" + variable.asText());
                }
            }
        }
        String body = resources.apply(path);
        if (body == null) {
            throw new IllegalStateException("缺少提示词模板资源：" + path + "（" + id + "）");
        }
        if (body.isBlank()) {
            throw new IllegalStateException("提示词模板资源为空：" + path + "（" + id + "）");
        }
        validatePlaceholders(id, body, declared);
        return new PromptTemplate(id, kind, purpose, version, path, body, declared);
    }

    private static void validatePlaceholders(String id, String body, Set<String> declared) {
        Set<String> found = new LinkedHashSet<>();
        int matched = 0;
        Matcher matcher = PLACEHOLDER.matcher(body);
        while (matcher.find()) {
            found.add(matcher.group(1));
            matched++;
        }
        if (countOccurrences(body, PLACEHOLDER_OPEN) != matched) {
            throw new IllegalStateException("提示词模板 " + id + " 含无法识别的占位符");
        }
        Set<String> undeclared = new LinkedHashSet<>(found);
        undeclared.removeAll(declared);
        if (!undeclared.isEmpty()) {
            throw new IllegalStateException("提示词模板 " + id + " 使用未声明变量：" + undeclared);
        }
        Set<String> unused = new LinkedHashSet<>(declared);
        unused.removeAll(found);
        if (!unused.isEmpty()) {
            throw new IllegalStateException("提示词模板 " + id + " 声明了正文未使用的变量：" + unused);
        }
    }

    private static int countOccurrences(String text, String token) {
        int count = 0;
        int index = text.indexOf(token);
        while (index >= 0) {
            count++;
            index = text.indexOf(token, index + token.length());
        }
        return count;
    }

    private static String requiredText(JsonNode entry, String field) {
        JsonNode value = entry.path(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new IllegalStateException("提示词模板清单缺少字段：" + field);
        }
        return value.asText();
    }

    private static String readResource(ClassLoader loader, String path) {
        try (InputStream stream = loader.getResourceAsStream(path)) {
            if (stream == null) {
                return null;
            }
            byte[] bytes = stream.readAllBytes();
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取提示词模板资源失败：" + path, e);
        }
    }
}
