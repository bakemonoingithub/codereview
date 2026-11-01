package com.codereview.agent.prompt;

/**
 * 统一组装入口的用途。
 *
 * <p>用途决定默认指令、输出协议与计量口径，调用方不能自行追加另一套协议。内置指令与协议
 * 正文不再写在本类中，而是由 classpath 的 {@code prompts/manifest.json} 按用途关联到
 * {@code instruction.<WIRE>} 与 {@code protocol.<WIRE>} 模板；本枚举只保留元数据，
 * 便于历史记录与工具读取时识别用途。
 *
 * <p>新增用途必须同时提供清单条目与模板正文，否则组装入口在读取时直接失败，不会静默
 * 回退为空提示词。
 */
public enum PromptPurpose {

    ISSUE_FILE("ISSUE_FILE", true),
    ISSUE_DIFF("ISSUE_DIFF", true),
    PATTERN("PATTERN", true),
    COUPLING("COUPLING", true),
    REPORT("REPORT", false),
    AGENT("AGENT", false),
    AGENT_TOOL_CHECK("AGENT_TOOL_CHECK", false),
    VALIDATION("VALIDATION", false);

    private final String wireName;
    private final boolean jsonMode;

    PromptPurpose(String wireName, boolean jsonMode) {
        this.wireName = wireName;
        this.jsonMode = jsonMode;
    }

    public String wireName() {
        return wireName;
    }

    public boolean jsonMode() {
        return jsonMode;
    }

    public boolean isSpecialist() {
        return this == ISSUE_FILE || this == ISSUE_DIFF || this == PATTERN || this == COUPLING;
    }
}
