package com.codereview.agent.invocation;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 一次逻辑调用的结果（可能包含多次传输尝试）。
 *
 * <p>{@code callId} 是最后一次尝试的记录 ID；每次尝试在数据库各有一条记录。
 * {@code inputEstimate} 与实际 usage 分开保存，前者是发送前判断容量的依据。
 */
public record InvocationResult(String callId, String content, JsonNode usage, String finishReason,
                               int attempts, int inputEstimate, int outputReserve,
                               String estimateMethod, String adapterVersion) {
}
