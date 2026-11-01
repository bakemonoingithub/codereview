package com.codereview.agent.contract;

import java.util.List;

/**
 * 业务输出契约中的一条字段声明。
 *
 * <p>声明同时被两处消费：模型可见的说明（专项协议示例、工具 JSON Schema）与运行时结构
 * 校验（必填、类型、枚举、长度、数量）。生成的 Schema、示例及明示数字读取同一声明；
 * 材料资格等业务说明另由维护关联和正反例检查，不要求全部从字段属性生成。
 *
 * <p>{@code example} 只用于生成给模型看的 JSON 示例；工具参数不需要示例时为 {@code null}。
 * {@code description} 只用于工具参数说明，专项协议不使用。
 */
public sealed interface ContractField permits ContractField.TextField, ContractField.EnumField,
        ContractField.IntField, ContractField.ArrayField, ContractField.ObjectField {

    String name();

    boolean required();

    /** 生成示例时使用的占位值；数组与对象由结构生成，返回 {@code null}。 */
    String example();

    /**
     * 字符串字段。
     *
     * <p>{@code maxChars} 是运行时校验长度；{@code schemaMaxLength} 是工具 JSON Schema
     * 输出的 {@code maxLength}。两者分开是因为历史行为并不完全一致（例如引用材料 ID
     * 的 schema 有长度上限，但提交解析依赖后续材料校验）。{@code nonBlank} 为
     * {@code false} 时允许空串，但仍必须是字符串。
     */
    record TextField(String name, boolean required, boolean nonBlank, Integer maxChars,
                     Integer schemaMaxLength, String pattern, String description, String example)
            implements ContractField {
        /** 默认 Schema 与运行时共用长度；仅历史差异使用完整构造器覆盖。 */
        public TextField(String name, boolean required, boolean nonBlank, Integer maxChars,
                         String pattern, String description, String example) {
            this(name, required, nonBlank, maxChars, maxChars, pattern, description, example);
        }
    }

    /** 枚举字段；示例按声明顺序用 {@code |} 连接，与协议正文一致。 */
    record EnumField(String name, boolean required, List<String> values, String description)
            implements ContractField {

        public EnumField {
            values = List.copyOf(values);
        }

        @Override
        public String example() {
            return String.join("|", values);
        }
    }

    /** 整数字段；{@code minimum}/{@code maximum} 只用于工具 Schema 与示例，结构校验只查整数类型。 */
    record IntField(String name, boolean required, Integer minimum, Integer maximum,
                    Integer schemaDefault, String description, String example)
            implements ContractField {
    }

    /**
     * 数组字段。
     *
     * <p>{@code emptyExample} 控制示例渲染为 {@code []}；{@code noteEmpty} 表示协议正文
     * 需要写“该数组可为空”；{@code tooFewNote} 是少于 {@code minItems} 时的说明。
     */
    record ArrayField(String name, boolean required, Integer minItems, Integer maxItems,
                      ContractField items, boolean emptyExample, boolean noteEmpty,
                      String tooFewNote, String description) implements ContractField {

        public String minimumNote() {
            String note = tooFewNote == null ? "至少需要 {min} 项" : tooFewNote;
            return note.replace("{min}", String.valueOf(minItems))
                    .replace("{minZh}", count(minItems));
        }

        public static String count(Integer value) {
            return value != null && value == 1 ? "一" : String.valueOf(value);
        }

        @Override
        public String example() {
            return null;
        }
    }

    /** 嵌套对象；作为数组元素时 {@code name} 可为 {@code null}。 */
    record ObjectField(String name, boolean required, List<ContractField> fields,
                       String description) implements ContractField {

        public ObjectField {
            fields = List.copyOf(fields);
        }

        @Override
        public String example() {
            return null;
        }
    }
}
