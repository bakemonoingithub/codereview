package com.codereview.agent.prompt;

/**
 * 统一组装入口的用途。
 *
 * <p>用途决定默认指令与输出协议，调用方不能自行追加另一套协议。新增用途必须同时
 * 定义协议文本与计量口径，避免出现"绕过组装直接拼 messages"的入口。
 */
public enum PromptPurpose {

    ISSUE_FILE("ISSUE_FILE", true, """
            根据给定的目标文件与有限辅助声明，找出可由代码和规则直接说明的问题。
            每条问题必须给出原因、可执行建议和至少一处目标位置；没有证据时不要为了填满数组而生成。
            未知依赖的行为只能列为边界或限制，不能用印象断言。"""),

    ISSUE_DIFF("ISSUE_DIFF", true, """
            检查此次提交差异引入或违反选定规则的问题，位置必须落在本次变更的前后侧。
            每条问题必须给出 changeRelation，说明与本次变更的关联；历史问题不自动归入本次。
            删除行允许使用 BEFORE 侧位置，新增行只能使用 AFTER 侧位置。"""),

    PATTERN("PATTERN", true, """
            识别以目标文件为中心的设计模式候选、角色与代码依据。
            不宣称模块完整实现，也不声称所有候选均适用；每个候选至少包含一处目标依据。
            无法归入经典模式时可使用 OTHER，但必须解释。"""),

    COUPLING("COUPLING", true, """
            根据服务端已计算的文件级依赖事实解释关系与风险。
            不得猜测缺失的指标，不得生成全仓指标或未计算出的 fan-in／instability。
            只能引用给定的 dependencyId 与材料位置。"""),

    REPORT("REPORT", false, """
            按给定事实、指标与冻结来源生成 Markdown 报告。正文必须引用来源序号，
            不得编造数据；引用必须指向冻结来源。材料不足时明确写出缺失，不静默省略。"""),

    AGENT("AGENT", false, """
            在只读工具范围内调查问题，输出少量固定信息与 Markdown 结论。
            不得声称执行了未实际调用的工具，也不得把计划写成已完成。"""),

    VALIDATION("VALIDATION", false, """
            这是一次连通性验证。只输出一个最小 JSON 对象 {"ok":true}，不分析任何业务内容。""");

    private final String wireName;
    private final boolean jsonMode;
    private final String builtInInstruction;

    PromptPurpose(String wireName, boolean jsonMode, String builtInInstruction) {
        this.wireName = wireName;
        this.jsonMode = jsonMode;
        this.builtInInstruction = builtInInstruction;
    }

    public String wireName() {
        return wireName;
    }

    public boolean jsonMode() {
        return jsonMode;
    }

    public String builtInInstruction() {
        return builtInInstruction;
    }

    public boolean isSpecialist() {
        return this == ISSUE_FILE || this == ISSUE_DIFF || this == PATTERN || this == COUPLING;
    }
}
