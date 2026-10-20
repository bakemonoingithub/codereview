package com.codereview.agent.prompt;

import java.util.List;

/**
 * 组装结果：有序消息、协议版本和计量依据。
 *
 * <p>调用侧只能把这个对象交给模型适配器；不得再追加、改写或删除消息。
 * {@code inputEstimate} 是保守估算（UTF-8 字节上界），不是精确 tokenizer 结果。
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
                              String adapterVersion) {

    public AssembledPrompt {
        messages = List.copyOf(messages);
    }
}
