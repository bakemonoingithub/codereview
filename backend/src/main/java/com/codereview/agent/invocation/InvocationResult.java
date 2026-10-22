package com.codereview.agent.invocation;

import com.codereview.agent.prompt.ToolCall;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * 一次逻辑调用的结果（可能包含多次传输尝试）。
 *
 * <p>{@code callId} 是最后一次尝试的记录 ID；每次尝试在数据库各有一条记录。
 * {@code inputEstimate} 与实际 usage 分开保存，前者是发送前判断容量的依据。
 * {@code toolCalls} 是模型返回的原生工具调用；正文与工具调用可以同时存在。
 */
public record InvocationResult(String callId, String content, JsonNode usage, String finishReason,
                               int attempts, int inputEstimate, int outputReserve,
                               String estimateMethod, String adapterVersion,
                               List<ToolCall> toolCalls) {

    public InvocationResult {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    /** 无工具调用的既有结果形状；专项、报告与连通验证继续使用这个构造。 */
    public InvocationResult(String callId, String content, JsonNode usage, String finishReason,
                            int attempts, int inputEstimate, int outputReserve,
                            String estimateMethod, String adapterVersion) {
        this(callId, content, usage, finishReason, attempts, inputEstimate, outputReserve,
                estimateMethod, adapterVersion, List.of());
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }
}
