package com.codereview.agent.prompt;

import static com.codereview.agent.prompt.PromptSections.*;

/** 报告任务先于通用规则，冻结事实及诊断保持原有顺序。 */
final class ReportPromptStrategy implements PromptInputStrategy {
    private final PromptSystemRenderer systems;
    private final PromptSections sections;

    ReportPromptStrategy(PromptSystemRenderer systems, PromptSections sections) {
        this.systems = systems;
        this.sections = sections;
    }

    @Override
    public AssembledPrompt assemble(PromptInput input) {
        String system = systems.systemPrompt(input.purpose());
        StringBuilder user = new StringBuilder();
        appendReport(user, input);
        appendGeneralRule(user, input);
        appendFacts(user, input);
        appendMissing(user, input);
        sections.appendRetry(user, input);
        return PromptMessageFactory.plain(input, system, user.toString());
    }
}
