package com.codereview.agent.contract;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 一个业务输出的结构契约：字段顺序、必填、类型、枚举、长度、数量与未知字段策略。
 *
 * <p>同一份声明生成两样东西：
 * <ul>
 *   <li>模型可见的 JSON 示例与规则句（{@link #exampleJson()}、{@link #ruleSentence()}）；</li>
 *   <li>运行时结构检查（{@link #checkStructure(JsonNode, ContractCodes)}、{@link #checkValues(JsonNode,
 *       ContractCodes)}），由解析入口和提交解析调用。</li>
 * </ul>
 *
 * <p>条件规则（材料归属、行号范围、补件要求、未解决事项等）仍由业务代码实现；本类只覆盖
 * 可由字段声明直接表达的规则，避免把动态判断写成配置。
 */
public record JsonContract(String name, boolean rejectUnknownFields, List<ContractField> fields,
                           String ruleNote) {

    public JsonContract {
        fields = List.copyOf(fields);
    }

    /** 按字段名取声明；不存在返回 {@code null}。 */
    public ContractField field(String fieldName) {
        for (ContractField field : fields) {
            if (field.name() != null && field.name().equals(fieldName)) {
                return field;
            }
        }
        return null;
    }

    /** 模型可见的 JSON 对象示例，紧凑格式，字段顺序与声明一致。 */
    public String exampleJson() {
        StringBuilder out = new StringBuilder();
        appendObject(out, fields);
        return out.toString();
    }

    /** 协议末尾的规则句：业务要点、可为空的数组、未知字段策略。 */
    public String ruleSentence() {
        List<String> parts = new ArrayList<>();
        String note = ruleNote;
        if (note != null && !note.isBlank()) {
            parts.add(note.strip());
        }
        for (ContractField field : fields) {
            if (field instanceof ContractField.ArrayField array && array.noteEmpty()) {
                parts.add(array.name() + (array.minItems() == null || array.minItems() == 0
                        ? " 可为空" : " 至少需要 " + array.minItems() + " 项"));
            }
        }
        if (rejectUnknownFields) {
            parts.add("不要输出未知字段");
        }
        return String.join("；", parts) + "。";
    }

    /** 协议结构段：示例 + 规则句；由模板插入，模板只保留段落编排。 */
    public String protocolBlock() {
        return exampleJson() + "\n" + ruleSentence();
    }

    /**
     * 结构检查：未知字段、必填数组是否存在且为数组。
     *
     * <p>必填数组必须在 null 被归一化为空数组之前判断，否则“缺少字段”会被误当成“空数组”。
     * 可选数组类型错误留给 {@link #checkValues}，以保持改造前的错误分类。
     */
    public List<ContractViolation> checkStructure(JsonNode root, ContractCodes codes) {
        List<ContractViolation> violations = new ArrayList<>();
        structure(root, fields, null, codes, violations);
        return List.copyOf(violations);
    }

    /**
     * 值检查：必填文本、枚举、整数类型、数组边界与元素。
     *
     * <p>只检查声明能表达的规则；整数上下限不在此处校验，避免改变“先映射再按业务范围
     * 报错”的既有分类。
     */
    public List<ContractViolation> checkValues(JsonNode root, ContractCodes codes) {
        List<ContractViolation> violations = new ArrayList<>();
        values(root, fields, null, codes, violations);
        return List.copyOf(violations);
    }

    private void structure(JsonNode node, List<ContractField> declared, String path,
                           ContractCodes codes, List<ContractViolation> violations) {
        if (node == null || !node.isObject()) {
            return;
        }
        if (rejectUnknownFields) {
            Set<String> known = new LinkedHashSet<>();
            for (ContractField field : declared) {
                known.add(field.name());
            }
            node.fieldNames().forEachRemaining(field -> {
                if (!known.contains(field)) {
                    violations.add(new ContractViolation(codes.unknownField(),
                            path(path, field) + " 含未知字段：" + field));
                }
            });
        }
        for (ContractField field : declared) {
            JsonNode value = node.get(field.name());
            if (field instanceof ContractField.ArrayField array) {
                if (array.required() && (value == null || !value.isArray())) {
                    violations.add(new ContractViolation(codes.missingField(),
                            path(path, field.name()) + " 必须是数组"));
                    continue;
                }
                if (value != null && value.isArray()
                        && array.items() instanceof ContractField.ObjectField item) {
                    for (int index = 0; index < value.size(); index++) {
                        JsonNode element = value.get(index);
                        if (element == null || !element.isObject()) {
                            // 旧解析入口先检查条目内必填数组，再映射对象。
                            for (ContractField nested : item.fields()) {
                                if (nested instanceof ContractField.ArrayField required && required.required()) {
                                    violations.add(new ContractViolation(codes.missingField(),
                                            itemPath(path, field.name(), index) + "." + nested.name() + " 必须是数组"));
                                    break;
                                }
                            }
                        }
                        if (element != null && element.isObject()) {
                            structure(element, item.fields(), itemPath(path, field.name(), index),
                                    codes, violations);
                        }
                    }
                }
            } else if (field instanceof ContractField.ObjectField object
                    && value != null && value.isObject()) {
                structure(value, object.fields(), path(path, field.name()), codes, violations);
            }
        }
    }

    private void values(JsonNode node, List<ContractField> declared, String path,
                        ContractCodes codes, List<ContractViolation> violations) {
        if (node == null || !node.isObject()) {
            return;
        }
        for (ContractField field : declared) {
            JsonNode value = node.get(field.name());
            value(field, value, path(path, field.name()), codes, violations);
        }
    }

    private void value(ContractField field, JsonNode value, String path, ContractCodes codes,
                       List<ContractViolation> violations) {
        if (value == null || value.isNull()) {
            if (field.name() == null) {
                violations.add(new ContractViolation(codes.nullItem(), path + " 不能为 null"));
            } else if (field instanceof ContractField.EnumField enumeration) {
                violations.add(new ContractViolation(codes.invalidEnum(), path + " 只能是 "
                        + String.join("/", enumeration.values()) + "，实际为 null"));
            } else if (field instanceof ContractField.TextField text
                    && (text.required() || text.nonBlank())) {
                violations.add(new ContractViolation(codes.missingField(), path + " 不能为空"));
            } else if (field instanceof ContractField.IntField integer && integer.required()) {
                violations.add(new ContractViolation(codes.missingField(), path + " 不能为空"));
            }
            return;
        }
        if (field instanceof ContractField.TextField text) {
            if (!value.isTextual()) {
                violations.add(new ContractViolation(codes.typeMismatch(), path + " 必须是字符串"));
                return;
            }
            String content = value.asText();
            if (text.nonBlank() && content.isBlank()) {
                violations.add(new ContractViolation(codes.missingField(), path + " 不能为空"));
            } else if (text.maxChars() != null && content.length() > text.maxChars()) {
                violations.add(new ContractViolation(codes.tooLong(),
                        path + " 超过 " + text.maxChars() + " 字符"));
            }
            return;
        }
        if (field instanceof ContractField.EnumField enumeration) {
            if (!value.isTextual()) {
                violations.add(new ContractViolation(codes.typeMismatch(), path + " 必须是字符串"));
                return;
            }
            if (!enumeration.values().contains(value.asText())) {
                violations.add(new ContractViolation(codes.invalidEnum(), path + " 只能是 "
                        + String.join("/", enumeration.values()) + "，实际为 " + value.asText()));
            }
            return;
        }
        if (field instanceof ContractField.IntField) {
            if (!value.isIntegralNumber() || !value.canConvertToInt()) {
                violations.add(new ContractViolation(codes.typeMismatch(), path + " 必须是整数"));
            }
            return;
        }
        if (field instanceof ContractField.ArrayField array) {
            if (!value.isArray()) {
                violations.add(new ContractViolation(codes.typeMismatch(), path + " 必须是数组"));
                return;
            }
            if (array.minItems() != null && value.size() < array.minItems()) {
                String note = array.minimumNote();
                violations.add(new ContractViolation(codes.tooFew(), path + " " + note));
            }
            if (array.maxItems() != null && value.size() > array.maxItems()) {
                violations.add(new ContractViolation(codes.tooMany(),
                        path + " 最多 " + array.maxItems() + " 项"));
            }
            for (int index = 0; index < value.size(); index++) {
                value(array.items(), value.get(index), path + "[" + index + "]", codes, violations);
            }
            return;
        }
        if (field instanceof ContractField.ObjectField object) {
            if (!value.isObject()) {
                violations.add(new ContractViolation(codes.typeMismatch(), path + " 必须是对象"));
                return;
            }
            values(value, object.fields(), path, codes, violations);
        }
    }

    private static void appendObject(StringBuilder out, List<ContractField> declared) {
        out.append('{');
        boolean first = true;
        for (ContractField field : declared) {
            if (!first) {
                out.append(',');
            }
            first = false;
            out.append('"').append(field.name()).append("\":");
            appendValue(out, field);
        }
        out.append('}');
    }

    private static void appendValue(StringBuilder out, ContractField field) {
        if (field instanceof ContractField.TextField text) {
            out.append(quote(text.example()));
        } else if (field instanceof ContractField.EnumField enumeration) {
            out.append(quote(enumeration.example()));
        } else if (field instanceof ContractField.IntField integer) {
            out.append(integer.example());
        } else if (field instanceof ContractField.ArrayField array) {
            if (array.emptyExample() || array.items() == null) {
                out.append("[]");
            } else {
                out.append('[');
                appendValue(out, array.items());
                out.append(']');
            }
        } else if (field instanceof ContractField.ObjectField object) {
            appendObject(out, object.fields());
        }
    }

    private static String quote(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder(value.length() + 2);
        out.append('"');
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            switch (ch) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(ch);
            }
        }
        return out.append('"').toString();
    }

    private static String path(String parent, String field) {
        return parent == null || parent.isEmpty() ? field : parent + "." + field;
    }

    private static String itemPath(String parent, String field, int index) {
        return path(parent, field) + "[" + index + "]";
    }
}
