package com.codereview.agent.api;

/** v2 错误体；{@code requestId} 来自链路追踪，用于把用户反馈对到日志。 */
public record ApiErrorBody(String code, String message, boolean retryable, String requestId, Object details) {
}
