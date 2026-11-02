package com.codereview.agent.prompt;

import com.codereview.agent.prompt.template.PromptTemplateRegistry;
import java.util.Map;

/** 纠正事件只负责正文渲染；触发、计数、角色与插入位置仍由 AgentTaskWorker 管理。 */
final class AgentCorrectionRenderer {
    private final PromptTemplateRegistry templates;

    AgentCorrectionRenderer(PromptTemplateRegistry templates) {
        this.templates = templates;
    }

    public String correction(AgentCorrection event) {
        if (event instanceof AgentCorrection.ModelOutputInvalid invalid) {
            return templates.render("correction.MODEL_OUTPUT_INVALID",
                    Map.of("diagnostic", invalid.diagnostic()));
        }
        if (event instanceof AgentCorrection.MissingToolCall) {
            return templates.require("correction.MISSING_TOOL_CALL").body();
        }
        if (event instanceof AgentCorrection.InvalidToolBatch batch) {
            return templates.render("correction.INVALID_TOOL_BATCH",
                    Map.of("maxToolsPerTurn", String.valueOf(batch.maxToolsPerTurn())));
        }
        if (event instanceof AgentCorrection.RepeatedToolCalls) {
            return templates.require("correction.REPEATED_TOOL_CALLS").body();
        }
        throw new IllegalArgumentException("未知纠正事件：" + event);
    }
}
