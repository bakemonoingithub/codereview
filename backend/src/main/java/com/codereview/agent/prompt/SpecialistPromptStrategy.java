package com.codereview.agent.prompt;

import com.codereview.agent.api.ApiException;
import com.codereview.agent.api.ErrorCode;

/** 四种专项用途共用消息结构，并保持原有材料前置检查。 */
final class SpecialistPromptStrategy implements PromptInputStrategy {
    private final StandardPromptStrategy standard;

    SpecialistPromptStrategy(StandardPromptStrategy standard) {
        this.standard = standard;
    }

    @Override
    public AssembledPrompt assemble(PromptInput input) {
        if (input.targets().isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "专项组装至少需要一个目标材料");
        }
        return standard.assemble(input);
    }
}
