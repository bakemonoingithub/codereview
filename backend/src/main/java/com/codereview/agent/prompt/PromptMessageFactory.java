package com.codereview.agent.prompt;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** 公共消息封装与计量；保留两种原有口径，不调整容量算法。 */
final class PromptMessageFactory {
    private static final int MESSAGE_OVERHEAD = 16;

    private PromptMessageFactory() {
    }

    static AssembledPrompt plain(PromptInput input, String system, String user) {
        List<PromptMessage> messages = List.of(new PromptMessage("system", system), new PromptMessage("user", user));
        int bytes = utf8Bytes(system) + utf8Bytes(user);
        return new AssembledPrompt(input.purpose(), input.protocolVersion(), messages, system, user,
                input.purpose().jsonMode(), bytes, estimate(bytes, messages.size()),
                PromptAssemblyService.ESTIMATE_METHOD, PromptAssemblyService.ADAPTER_VERSION);
    }

    // 工具定义和完整历史一起计量；普通入口保留原有只计正文的口径。
    static AssembledPrompt withTools(PromptPurpose purpose, List<PromptMessage> messages,
                                      String system, String user, List<Map<String, Object>> tools) {
        int bytes = toolsBytes(tools);
        for (PromptMessage message : messages) {
            bytes += utf8Bytes(message.content());
            for (ToolCall call : message.toolCalls()) {
                bytes += utf8Bytes(call.name()) + utf8Bytes(call.arguments()) + 32;
            }
        }
        return new AssembledPrompt(purpose, "1", messages, system, user,
                false, bytes, estimate(bytes, messages.size()), PromptAssemblyService.ESTIMATE_METHOD,
                PromptAssemblyService.ADAPTER_VERSION, tools, "auto");
    }

    private static int estimate(int bytes, int messages) {
        return bytes + messages * MESSAGE_OVERHEAD + 64;
    }

    static int toolsBytes(List<Map<String, Object>> tools) {
        if (tools == null || tools.isEmpty()) {
            return 0;
        }
        try {
            return new ObjectMapper().writeValueAsString(tools).getBytes(StandardCharsets.UTF_8).length;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("工具定义序列化失败", e);
        }
    }

    static int utf8Bytes(String text) {
        return text == null ? 0 : text.getBytes(StandardCharsets.UTF_8).length;
    }
}
