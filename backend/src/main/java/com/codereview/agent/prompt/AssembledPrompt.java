package com.codereview.agent.prompt;

import java.util.List;
import java.util.Map;

/**
 * 组装结果：有序消息、工具定义、协议版本和计量依据。
 *
 * <p>调用侧只能把这个对象交给模型适配器；不得再追加、改写或删除消息。
 * {@code inputEstimate} 是保守估算（UTF-8 字节上界），已包含工具定义与完整历史，
 * 不是精确 tokenizer 结果。
 */
public record AssembledPrompt(PromptPurpose purpose,
                              String protocolVersion,
                              List<PromptMessage> messages,
                              String systemPrompt,
                              String userPrompt,
                              boolean jsonMode,
                              int inputBytes,
                              int inputEstimate,
                              String estimateMethod,
                              String adapterVersion,
                              List<Map<String, Object>> tools,
                              String toolChoice) {

    public AssembledPrompt {
        messages = List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
    }

    /** 无工具调用的既有组装结果；专项、报告与连通验证继续使用这个形状。 */
    public AssembledPrompt(PromptPurpose purpose, String protocolVersion,
                           List<PromptMessage> messages, String systemPrompt, String userPrompt,
                           boolean jsonMode, int inputBytes, int inputEstimate,
                           String estimateMethod, String adapterVersion) {
        this(purpose, protocolVersion, messages, systemPrompt, userPrompt, jsonMode, inputBytes,
                inputEstimate, estimateMethod, adapterVersion, List.of(), null);
    }

    public boolean hasTools() {
        return !tools.isEmpty();
    }
}
