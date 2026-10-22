package com.codereview.agent.invocation;

import com.codereview.agent.entity.AgentModelConfig;

/**
 * 调用时使用的模型配置快照。
 *
 * <p>配置可能在调用期间被修改；这里保存发起时的值，凭据只用于本次请求，
 * 不进入请求体、调用记录或响应。
 */
public record ModelSnapshot(String id, String name, String baseUrl, String modelName,
                            String credential, Integer contextWindow, Integer maxOutput,
                            String tokenizer, String protocol, Integer revision) {

    public static ModelSnapshot from(AgentModelConfig config) {
        return new ModelSnapshot(config.getId(), config.getName(), config.getBaseUrl(),
                config.getModelName(), config.getCredential(), config.getContextWindow(),
                config.getMaxOutput(), config.getTokenizer(),
                config.getProtocol() == null ? "OPENAI_CHAT" : config.getProtocol(),
                config.getRevision());
    }

    public boolean openAiCompatible() {
        return protocol == null || "OPENAI_CHAT".equals(protocol);
    }
}
