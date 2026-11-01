package com.codereview.agent.tools;

import com.codereview.agent.contract.CitationFormat;
import com.codereview.agent.contract.ContractField;
import com.codereview.agent.contract.JsonContract;
import com.codereview.agent.tools.capability.ToolCapability;
import com.codereview.agent.tools.capability.ToolParameter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent {@code submit_result} 的提交契约。
 *
 * <p>字段、必填、长度、数组上限、状态枚举与引用编号格式只在这里声明一次：
 * <ul>
 *   <li>{@link #tool()} 生成模型看到的工具定义（由 {@code AgentToolCapabilities} 选用）；</li>
 *   <li>{@link #contract()} 生成运行时结构检查（由 {@link AgentSubmission#parse} 使用）；</li>
 *   <li>{@link #protocolValues()} 提供 Agent 协议段中的字段名与编号示例。</li>
 * </ul>
 *
 * <p>引用是否指向实际送入模型的材料、正文是否实际使用引用、未解决事项是否满足材料不足
 * 状态等条件仍由 {@link AgentSubmission#validate} 按业务上下文判断。
 */
public final class AgentSubmissionContract {

    public static final String ANSWERED_STATUS = "ANSWERED";
    public static final String INSUFFICIENT_EVIDENCE_STATUS = "INSUFFICIENT_EVIDENCE";

    // 第一档：数量与格式共同声明；材料资格及范围判断由 AgentSubmission.validate 负责。
    static final int MIN_ANSWER_CITATIONS = 1;
    static final int MIN_UNRESOLVED_ITEMS = 1;
    static final CitationFormat CITATIONS = new CitationFormat("E", 3);

    /** 正文引用编号：E1、E2…，不带方括号；正文中写 [E1]、[E2]。 */
    public static final String CITATION_ID_PATTERN = CITATIONS.idPattern();
    public static final String CITATION_ID_SCHEMA_PATTERN = CITATIONS.schemaPattern();
    public static final String CITATION_ID_EXAMPLE = CITATIONS.id(1) + "、" + CITATIONS.id(2);

    /** 仅演示提交结构；调用方必须替换材料 ID 和行号。 */
    public static final String ANSWERED_EXAMPLE = """
            {"title":"定位结果","summary":"结论由已读取材料支持。","answerStatus":"%s",
             "markdown":"结论由已读取材料支持。%s",
             "citations":[{"id":"%s","materialId":"<实际materialId>","startLine":12,"endLine":13}],
             "unresolved":[]}
            """.formatted(ANSWERED_STATUS, CITATIONS.marker(1), CITATIONS.id(1));
    public static final String INSUFFICIENT_EVIDENCE_EXAMPLE = """
            {"title":"材料不足","summary":"尚缺关键实现材料。","answerStatus":"%s",
             "markdown":"未取得关键实现，无法确认结论。","citations":[],
             "unresolved":["尚未读取到关键实现。"]}
            """.formatted(INSUFFICIENT_EVIDENCE_STATUS);

    private static final List<ContractField> FIELDS = fields();
    private static final JsonContract CONTRACT =
            new JsonContract("submit_result", true, FIELDS, null);

    private AgentSubmissionContract() {
    }

    /** 运行时结构契约；与工具定义读取同一份字段声明。 */
    public static JsonContract contract() {
        return CONTRACT;
    }

    /** Agent 协议段变量；模板只保留语句编排。 */
    public static Map<String, String> protocolValues() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("submitTool", AgentToolNames.SUBMIT_RESULT);
        values.put("citationsField", "citations");
        values.put("citationIdExample", CITATION_ID_EXAMPLE);
        values.put("materialIdField", "materialId");
        return Map.copyOf(values);
    }

    /** {@code submit_result} 的中立工具定义；字段顺序与说明沿用改造前正文。 */
    public static ToolCapability tool() {
        return tool(CONTRACT);
    }

    static ToolCapability tool(JsonContract contract) {
        List<ToolParameter> parameters = new ArrayList<>();
        for (ContractField field : contract.fields()) {
            parameters.add(parameter(field));
        }
        return new ToolCapability(AgentToolNames.SUBMIT_RESULT, description(),
                List.copyOf(parameters));
    }

    private static String description() {
        return "结束调查并提交结果，必须单独调用，不能与其他工具同批。"
                + "citations.id 使用 %s、%s，markdown 中必须写成 %s、%s，不能只填写引用数组。"
                        .formatted(CITATIONS.id(1), CITATIONS.id(2), CITATIONS.marker(1), CITATIONS.marker(2))
                + "引用必须来自本次任务实际返回且已送入模型的材料，行号不得超出该 materialId 的返回范围。"
                + toolConditions()
                + "以下示例仅演示格式，materialId 占位符和行号必须替换为本次实际材料值，不能照抄："
                + ANSWERED_EXAMPLE
                + "未取得证据时的提交示例：" + INSUFFICIENT_EVIDENCE_EXAMPLE;
    }

    private static String count(int value) { return ContractField.ArrayField.count(value); }

    private static String toolConditions() {
        return ANSWERED_STATUS + " 至少" + count(MIN_ANSWER_CITATIONS)
                + "个正文实际使用的有效引用；" + INSUFFICIENT_EVIDENCE_STATUS
                + " 必须填写" + (MIN_UNRESOLVED_ITEMS == 1 ? "非空 unresolved"
                : "至少" + count(MIN_UNRESOLVED_ITEMS) + "项 unresolved") + "。";
    }

    private static String statusConditions() {
        return ANSWERED_STATUS + " 要求至少" + count(MIN_ANSWER_CITATIONS)
                + "项有效引用且正文实际使用；" + INSUFFICIENT_EVIDENCE_STATUS
                + " 要求 unresolved " + (MIN_UNRESOLVED_ITEMS == 1 ? "非空"
                : "至少" + count(MIN_UNRESOLVED_ITEMS) + "项") + "，可无引用。";
    }

    private static String citationDescription() {
        return "引用定义；" + ANSWERED_STATUS + " 必须提供至少" + count(MIN_ANSWER_CITATIONS)
                + "项有效定义，并在 markdown 中以 " + CITATIONS.marker(1)
                + " 等标记实际引用。只填数组不算正文引用；正文未使用的定义不会发布。";
    }

    private static String unresolvedDescription() {
        return "具体缺失材料或无法确认的问题；" + INSUFFICIENT_EVIDENCE_STATUS
                + " 至少填写" + count(MIN_UNRESOLVED_ITEMS) + "项非空文本，无未解决事项时填空数组。";
    }

    // 维护关联 BRC-M01：下面的材料、行范围与正文使用说明可自然表述；
    // 修改这些语义时同步检查 AgentSubmission.validate 及维护索引，不为文案建立执行规则对象。
    private static List<ContractField> fields() {
        List<ContractField> citation = List.of(
                new ContractField.TextField("id", true, true, null, null,
                        CITATION_ID_SCHEMA_PATTERN,
                        "本次结果内唯一的引用编号，如 %s、%s，不带方括号；正文中写 %s、%s，不要使用 S1。"
                                .formatted(CITATIONS.id(1), CITATIONS.id(2), CITATIONS.marker(1), CITATIONS.marker(2)),
                        null),
                new ContractField.TextField("materialId", true, true, null, 26, null,
                        "原样填写本次任务只读工具返回并已送入模型的材料 ID；不能编造，也不能从参考结果或其他任务复制。",
                        null),
                new ContractField.IntField("startLine", true, 1, null, null,
                        "引用起始行，包含端点，必须位于该 materialId 的实际返回行范围内。", null),
                new ContractField.IntField("endLine", true, 1, null, null,
                        "引用结束行，包含端点，不能小于 startLine，也不能超出该材料末行。"
                                + "不能把同一文件的多份材料范围拼到一个 materialId 上；需要更大范围时先调用 read_code。",
                        null));
        return List.of(
                new ContractField.TextField("title", true, true, 200, null, null, null),
                new ContractField.TextField("summary", true, true, 1000, null, null, null),
                new ContractField.EnumField("answerStatus", true,
                        List.of(ANSWERED_STATUS, INSUFFICIENT_EVIDENCE_STATUS),
                        statusConditions()),
                new ContractField.TextField("markdown", true, true, 24000, null,
                        ("结果正文；引用写在正文中，必须使用英文半角方括号，如：结论。%s。"
                                + "多项写作 %s%s，不能写成（%s）、见 %s 或 [%s,%s]；每个标记必须对应 citations 中有效的同名 id。")
                                .formatted(CITATIONS.marker(1), CITATIONS.marker(1), CITATIONS.marker(2),
                                        CITATIONS.id(1), CITATIONS.id(1), CITATIONS.id(1), CITATIONS.id(2)),
                        null),
                new ContractField.ArrayField("citations", false, null, 100,
                        new ContractField.ObjectField(null, false, citation, null),
                        false, false, null,
                        citationDescription()),
                new ContractField.ArrayField("unresolved", false, null, 20,
                        new ContractField.TextField(null, false, true, 1000, null, null, null),
                        false, false, null,
                        unresolvedDescription()));
    }

    private static ToolParameter parameter(ContractField field) {
        if (field instanceof ContractField.TextField text) {
            if (text.pattern() != null) {
                return ToolParameter.pattern(text.name(), text.required(), text.description(),
                        text.pattern());
            }
            return ToolParameter.text(text.name(), text.required(), text.description(),
                    text.schemaMaxLength());
        }
        if (field instanceof ContractField.EnumField enumeration) {
            return ToolParameter.enumText(enumeration.name(), enumeration.required(),
                    enumeration.description(), enumeration.values());
        }
        if (field instanceof ContractField.IntField integer) {
            return new ToolParameter.IntegerValue(integer.name(), integer.required(),
                    integer.description(), integer.schemaDefault(), null, integer.minimum(),
                    integer.maximum());
        }
        if (field instanceof ContractField.ArrayField array) {
            return ToolParameter.array(array.name(), array.required(), array.description(),
                    array.maxItems(), parameter(array.items()));
        }
        ContractField.ObjectField object = (ContractField.ObjectField) field;
        List<ToolParameter> properties = object.fields().stream()
                .map(AgentSubmissionContract::parameter).toList();
        return ToolParameter.object(object.name(), object.required(), object.description(),
                properties);
    }
}
