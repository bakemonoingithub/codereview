package com.codereview.agent.prompt;

import static com.codereview.agent.prompt.PromptSections.*;

/** 普通消息结构：供专项委托及连通验证使用，保留通用入口的既有接受范围。 */
final class StandardPromptStrategy implements PromptInputStrategy {
    private final PromptSystemRenderer systems;
    private final PromptSections sections;

    StandardPromptStrategy(PromptSystemRenderer systems, PromptSections sections) {
        this.systems = systems;
        this.sections = sections;
    }

    @Override
    public AssembledPrompt assemble(PromptInput input) {
        String system = systems.systemPrompt(input.purpose());
        StringBuilder user = new StringBuilder();
        appendGeneralRule(user, input);
        appendNote(user, input);
        appendScope(user, input);
        appendTargets(user, input);
        appendAuxiliary(user, input);
        appendFacts(user, input);
        appendMissing(user, input);
        sections.appendRetry(user, input);
        return PromptMessageFactory.plain(input, system, user.toString());
    }
}
