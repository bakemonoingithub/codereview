package com.codereview.agent.prompt;

import java.util.List;

/**
 * 一条有序消息。
 *
 * <p>系统与用户消息只能有正文；assistant 消息可以同时带正文与工具调用；
 * tool 消息必须带 {@code tool_call_id}，把工具返回关联回模型发起的那个调用。
 * 只有 assistant 且带工具调用时允许正文为空。
 */
public record PromptMessage(String role, String content, List<ToolCall> toolCalls,
                            String toolCallId) {

    public PromptMessage {
        if (role == null || !List.of("system", "user", "assistant", "tool").contains(role)) {
            throw new IllegalArgumentException("不支持的消息角色：" + role);
        }
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        boolean assistant = "assistant".equals(role);
        boolean tool = "tool".equals(role);
        if (tool) {
            if (toolCallId == null || toolCallId.isBlank()) {
                throw new IllegalArgumentException("工具消息缺少 tool_call_id");
            }
            if (!toolCalls.isEmpty()) {
                throw new IllegalArgumentException("工具消息不能携带新的工具调用");
            }
        } else if (toolCallId != null) {
            throw new IllegalArgumentException("只有工具消息可以带 tool_call_id");
        }
        if (!assistant && !toolCalls.isEmpty()) {
            throw new IllegalArgumentException(role + " 消息不能携带工具调用");
        }
        if (content == null && toolCalls.isEmpty()) {
            throw new IllegalArgumentException("消息正文不能为空");
        }
    }

    public PromptMessage(String role, String content) {
        this(role, content, List.of(), null);
    }

    public static PromptMessage assistantToolCalls(List<ToolCall> toolCalls, String content) {
        return new PromptMessage("assistant", content, toolCalls, null);
    }

    public static PromptMessage toolResult(String toolCallId, String content) {
        return new PromptMessage("tool", content, List.of(), toolCallId);
    }
}
