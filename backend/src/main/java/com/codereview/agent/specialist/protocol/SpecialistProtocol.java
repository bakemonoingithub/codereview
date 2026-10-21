package com.codereview.agent.specialist.protocol;

import com.codereview.agent.prompt.PromptPurpose;

/**
 * 三类专项的版本化输出协议。
 *
 * <p>协议文本只在这里维护；{@code PromptAssemblyService} 只负责把内置指令与协议放进
 * system 消息，调用方不能自行追加另一套 schema。协议版本进入结果封套和组装输入，
 * 历史结果按保存时的版本阅读。
 *
 * <p>协议只做结构、类型、必填和位置范围检查；不判断问题真伪、模式是否经典或耦合解释
 * 是否正确，这些留给人工反馈。
 */
public final class SpecialistProtocol {

    /** 协议与结果封套版本；字段不兼容时递增。 */
    public static final String VERSION = "1";
    /** 内置指令版本；与用户策略/文件说明分开，便于历史调用回看。 */
    public static final String INSTRUCTION_VERSION = "1";

    private static final String ISSUE_JSON = """
            只输出一个 JSON 对象，不要 Markdown 代码围栏或额外说明：
            {"summary":"一句话结论","issues":[{"category":"CORRECTNESS|SECURITY|RELIABILITY|PERFORMANCE|MAINTAINABILITY|RULE","severity":"HIGH|MEDIUM|LOW","title":"简短标题","locations":[{"materialId":"target-1","startLine":1,"endLine":2}],"reason":"可由材料支持的原因","suggestion":"可执行建议","evidence":[{"materialId":"aux-1","startLine":3,"endLine":4}],"changeRelation":"差异审查必填，文件审查为 null"}],"limitations":[]}
            位置必须落在对应材料的原始行号范围内；issues 可为空；不要输出未知字段。""";

    private SpecialistProtocol() {
    }

    public static String outputSchema(PromptPurpose purpose) {
        return switch (purpose) {
            case ISSUE_FILE, ISSUE_DIFF -> ISSUE_JSON;
            case PATTERN -> """
                    只输出一个 JSON 对象：
                    {"summary":"一句话结论","patterns":[{"name":"模式名","family":"CREATIONAL|STRUCTURAL|BEHAVIORAL|OTHER","role":"目标承担的角色","explanation":"可见结构与模式对应关系","evidence":[{"materialId":"target-1","startLine":1,"endLine":2}]}],"limitations":[]}
                    每个候选至少一处目标依据；patterns 可为空；不要输出未知字段。""";
            case COUPLING -> """
                    只输出一个 JSON 对象：
                    {"summary":"一句话结论","observations":[{"title":"短标题","explanation":"只解释给定依赖事实","dependencyIds":["dep-1"],"evidence":[{"materialId":"target-1","startLine":1,"endLine":2}],"suggestion":"可选建议"}],"limitations":[]}
                    dependencyIds 必须来自给定事实；observations 可为空；不要输出未知字段。""";
            case REPORT, AGENT -> """
                    输出 Markdown 正文。引用使用 [S1] 形式且只能指向给定来源；
                    事实、数字和位置必须来自输入；缺失写在正文对应章节，不静默省略。""";
            case VALIDATION -> """
                    只输出一个 JSON 对象，例如 {"ok":true}；必须包含布尔字段 ok 且为 true。
                    不要输出 Markdown、代码围栏或额外说明。""";
        };
    }

    public static String systemSuffix(PromptPurpose purpose) {
        return "\n\n输出协议（protocolVersion=" + VERSION + "，purpose="
                + purpose.wireName() + "）：\n" + outputSchema(purpose);
    }
}
