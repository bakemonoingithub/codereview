package com.codereview.agent.prompt;

import com.codereview.agent.prompt.template.PromptTemplateRegistry;
import com.codereview.agent.tools.capability.ToolCapability;
import com.codereview.agent.tools.protocol.OpenAiChatToolAdapter;
import java.util.List;

/** 工具验证首轮与回放轮；复用工具消息封装，不承担工具执行。 */
final class ToolCheckPromptStrategy {
    private final PromptTemplateRegistry templates;
    private final PromptSystemRenderer systems;

    ToolCheckPromptStrategy(PromptTemplateRegistry templates, PromptSystemRenderer systems) {
        this.templates = templates;
        this.systems = systems;
    }

    private String toolCheckUser() {
        return templates.require("agent.TOOL_CHECK_USER").body();
    }

    public AssembledPrompt assembleToolCheck(List<ToolCapability> capabilities) {
        String system = systems.systemPrompt(PromptPurpose.AGENT_TOOL_CHECK);
        String user = toolCheckUser();
        List<PromptMessage> messages = List.of(new PromptMessage("system", system),
                new PromptMessage("user", user));
        return PromptMessageFactory.withTools(PromptPurpose.AGENT_TOOL_CHECK, messages, system, user, OpenAiChatToolAdapter.tools(capabilities));
    }

    public AssembledPrompt assembleToolCheckFollowUp(List<ToolCapability> capabilities,
                                                     ToolCall call, String toolResult) {
        String system = systems.systemPrompt(PromptPurpose.AGENT_TOOL_CHECK);
        String user = toolCheckUser();
        List<PromptMessage> messages = List.of(
                new PromptMessage("system", system),
                new PromptMessage("user", user),
                PromptMessage.assistantToolCalls(List.of(call), null),
                PromptMessage.toolResult(call.id(), toolResult));
        return PromptMessageFactory.withTools(PromptPurpose.AGENT_TOOL_CHECK, messages, system, user, OpenAiChatToolAdapter.tools(capabilities));
    }
}
