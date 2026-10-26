package com.codereview.agent.invocation;

import com.codereview.agent.api.ApiException;
import com.codereview.agent.api.ErrorCode;
import com.codereview.agent.prompt.AssembledPrompt;
import com.codereview.agent.prompt.PromptPurpose;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 发送前容量判断。
 *
 * <p>输入使用组装服务给出的 UTF-8 字节上界估算；输出预留默认 4096，且不超过模型
 * 声明的最大输出；安全余量取 max(1024, 窗口的 10%)。三者之和不超过声明的上下文窗口
 * 才允许发送。模型仍可能报超限，那属于下一次授权范围，不在这里自动拆分。
 */
@Service
public class ModelCapacityService {

    private final ModelInvocationProperties properties;

    public ModelCapacityService(ModelInvocationProperties properties) {
        this.properties = properties;
    }

    public CapacityEstimate estimate(AssembledPrompt prompt, ModelSnapshot model) {
        return estimate(prompt, model, null);
    }

    /**
     * @param outputOverride 调用方为本用途要求的输出预留；为空时使用公共默认值。
     *                       任何值都不会超过模型声明的最大输出。
     */
    public CapacityEstimate estimate(AssembledPrompt prompt, ModelSnapshot model, Integer outputOverride) {
        boolean validation = prompt.purpose() == PromptPurpose.VALIDATION;
        int outputReserve = validation ? limitOutput(properties.getValidationOutputTokens(), model)
                : outputOverride == null ? outputReserve(model) : limitOutput(outputOverride, model);
        if (model.contextWindow() == null || model.contextWindow() <= 0) {
            if (!validation) {
                throw new ApiException(ErrorCode.CONFIG_INCOMPLETE,
                        "模型未声明上下文窗口，无法判断容量", Map.of("modelId", model.id()));
            }
            return new CapacityEstimate(prompt.inputEstimate(),
                    outputReserve, 0, Integer.MAX_VALUE,
                    prompt.estimateMethod());
        }
        int safetyReserve = configuredSafetyReserve(model.contextWindow());
        return new CapacityEstimate(prompt.inputEstimate(), outputReserve, safetyReserve,
                model.contextWindow(), prompt.estimateMethod());
    }

    public CapacityEstimate requireFits(AssembledPrompt prompt, ModelSnapshot model) {
        return requireFits(prompt, model, null);
    }

    public CapacityEstimate requireFits(AssembledPrompt prompt, ModelSnapshot model,
                                        Integer outputOverride) {
        CapacityEstimate estimate = estimate(prompt, model, outputOverride);
        if (!estimate.fits()) {
            throw new ApiException(ErrorCode.CONTEXT_LIMIT,
                    "完整请求超过模型上下文窗口，未发送",
                    Map.of("inputEstimate", estimate.inputEstimate(),
                            "outputReserve", estimate.outputReserve(),
                            "safetyReserve", estimate.safetyReserve(),
                            "required", estimate.required(),
                            "contextWindow", estimate.contextWindow(),
                            "method", estimate.method()));
        }
        return estimate;
    }

    public int outputReserve(ModelSnapshot model) {
        return limitOutput(properties.getOutputReserveTokens(), model);
    }

    private static int limitOutput(int configured, ModelSnapshot model) {
        if (model.maxOutput() != null && model.maxOutput() > 0) {
            return Math.min(configured, model.maxOutput());
        }
        return configured;
    }

    public int configuredSafetyReserve(int contextWindow) {
        return Math.max(properties.getSafetyReserveMinTokens(), (int) Math.ceil(contextWindow * properties.getSafetyReserveRatio()));
    }
    static int safetyReserve(int contextWindow) {
        return Math.max(1024, (int) Math.ceil(contextWindow * 0.10));
    }
}
