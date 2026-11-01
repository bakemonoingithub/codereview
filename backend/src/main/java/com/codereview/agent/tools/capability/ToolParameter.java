package com.codereview.agent.tools.capability;

import com.codereview.agent.tools.ToolLimits;

import java.util.List;

/**
 * 中立工具参数定义。
 *
 * <p>这里只描述"参数叫什么、是不是必填、取值范围多大、给模型看的说明是什么"，
 * 不出现任何模型供应商的消息格式。工具描述由协议适配层按本定义生成，参数校验由
 * {@code ToolArgumentParser} 读取同一份定义执行，因此 schema 与运行时校验不会各写一套约束。
 *
 * <p>{@link Role} 只标注字符串参数的**语义**（版本标识、路径、glob、游标等），
 * 用于校验分支与错误提示；schema 形状仍由 {@code pattern}、{@code maxChars} 等属性决定，
 * 保持与改造前逐字一致。
 */
public sealed interface ToolParameter
        permits ToolParameter.Text, ToolParameter.IntegerValue, ToolParameter.Array, ToolParameter.Object {

    String name();

    boolean required();

    /** 给模型看的参数说明；{@code null} 表示不输出 description 字段。 */
    String description();

    /** 字符串参数的校验语义；只影响程序侧校验，不改变 schema 文案。 */
    enum Role {
        /** 普通字符串。 */
        PLAIN,
        /** 冻结版本标识，只接受作用域给出的 V1…Vm。 */
        VERSION_REF,
        /** 冻结比较关系标识，只接受 Cn。 */
        COMPARISON_REF,
        /** 仓库内相对路径。 */
        PATH,
        /** 仓库内相对 glob。 */
        GLOB,
        /** 单行非空搜索内容。 */
        QUERY,
        /** 不透明游标，只原样回传上一页的值。 */
        CURSOR
    }

    record Text(String name, boolean required, String description, Role role,
                Integer maxChars, Integer minLength, String pattern, List<String> enumValues)
            implements ToolParameter {

        public Text {
            role = role == null ? Role.PLAIN : role;
            enumValues = enumValues == null ? List.of() : List.copyOf(enumValues);
        }
    }

    record IntegerValue(String name, boolean required, String description,
                        Integer defaultValue, Integer minimum, Integer maximum)
            implements ToolParameter {
    }

    record Array(String name, boolean required, String description,
                 Integer maxItems, ToolParameter items)
            implements ToolParameter {
    }

    /** 嵌套对象；作为数组元素时 {@code name} 可为 {@code null}。 */
    record Object(String name, boolean required, String description, List<ToolParameter> properties)
            implements ToolParameter {

        public Object {
            properties = properties == null ? List.of() : List.copyOf(properties);
        }
    }

    static Text text(String name, boolean required, String description, Integer maxChars) {
        return new Text(name, required, description, Role.PLAIN, maxChars, null, null, List.of());
    }

    static Text pattern(String name, boolean required, String description,
                        String pattern, Integer maxChars) {
        return new Text(name, required, description, Role.PLAIN, maxChars, null, pattern, List.of());
    }

    static Text enumText(String name, boolean required, String description, List<String> values) {
        return new Text(name, required, description, Role.PLAIN, null, null, null, values);
    }

    static Text versionRef(String name, String description) {
        return new Text(name, true, description, Role.VERSION_REF, 16, null, "^[A-Za-z0-9_-]{1,16}$",
                List.of());
    }

    static Text comparisonRef(String name, String description) {
        return new Text(name, true, description, Role.COMPARISON_REF, 16, null,
                "^[A-Za-z0-9_-]{1,16}$", List.of());
    }

    static Text path(String name, boolean required, String description) {
        return new Text(name, required, description, Role.PATH, ToolLimits.PATH_MAX_CHARS, null,
                null, List.of());
    }

    static Text glob(String name, boolean required, String description) {
        return new Text(name, required, description, Role.GLOB, ToolLimits.GLOB_MAX_CHARS, null,
                null, List.of());
    }

    static Text query(String name, String description) {
        return new Text(name, true, description, Role.QUERY, ToolLimits.SEARCH_QUERY_MAX_CHARS, 1,
                null, List.of());
    }

    static Text cursor(String description) {
        return new Text("cursor", false, description, Role.CURSOR, ToolLimits.CURSOR_MAX_CHARS,
                null, null, List.of());
    }

    static IntegerValue integer(String name, String description, Integer defaultValue,
                                Integer minimum, Integer maximum) {
        return new IntegerValue(name, false, description, defaultValue, minimum, maximum);
    }

    static Array array(String name, boolean required, String description, Integer maxItems,
                       ToolParameter items) {
        return new Array(name, required, description, maxItems, items);
    }

    static Object object(String name, boolean required, String description,
                         List<ToolParameter> properties) {
        return new Object(name, required, description, properties);
    }
}
