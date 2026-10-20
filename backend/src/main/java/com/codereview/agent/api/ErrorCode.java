package com.codereview.agent.api;

import org.springframework.http.HttpStatus;

/**
 * v2 错误码。HTTP 状态表达失败语义，`code` 给前端稳定判断，`retryable` 由服务端判定。
 *
 * <p>与旧接口的四位数字码不是一套：旧接口仍用 200 信封与 1xxx–7xxx，
 * v2 不解释旧码，旧页面也不读 v2 码。
 */
public enum ErrorCode {

    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", false),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", false),
    FORBIDDEN(HttpStatus.FORBIDDEN, "FORBIDDEN", false),
    REPO_UNAUTHORIZED(HttpStatus.FORBIDDEN, "REPO_UNAUTHORIZED", false),
    NOT_FOUND(HttpStatus.NOT_FOUND, "NOT_FOUND", false),
    CONFLICT(HttpStatus.CONFLICT, "CONFLICT", false),
    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", false),
    SOURCE_MOVED(HttpStatus.CONFLICT, "SOURCE_MOVED", false),
    REVISION_CONFLICT(HttpStatus.CONFLICT, "REVISION_CONFLICT", false),
    CONFIG_IN_USE(HttpStatus.CONFLICT, "CONFIG_IN_USE", false),
    REPO_UNREACHABLE(HttpStatus.UNPROCESSABLE_ENTITY, "REPO_UNREACHABLE", false),
    REPO_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "REPO_UNAVAILABLE", true),
    FILE_NOT_READABLE(HttpStatus.UNPROCESSABLE_ENTITY, "FILE_NOT_READABLE", false),
    SNAPSHOT_INCOMPLETE(HttpStatus.UNPROCESSABLE_ENTITY, "SNAPSHOT_INCOMPLETE", false),
    UNSUPPORTED_LANGUAGE(HttpStatus.UNPROCESSABLE_ENTITY, "UNSUPPORTED_LANGUAGE", false),
    CONTEXT_LIMIT(HttpStatus.UNPROCESSABLE_ENTITY, "CONTEXT_LIMIT", false),
    CONFIG_INCOMPLETE(HttpStatus.UNPROCESSABLE_ENTITY, "CONFIG_INCOMPLETE", false),
    MODEL_UNAUTHORIZED(HttpStatus.UNPROCESSABLE_ENTITY, "MODEL_UNAUTHORIZED", false),
    MODEL_BAD_REQUEST(HttpStatus.UNPROCESSABLE_ENTITY, "MODEL_BAD_REQUEST", false),
    MODEL_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "MODEL_UNAVAILABLE", true),
    MODEL_OUTPUT_INVALID(HttpStatus.UNPROCESSABLE_ENTITY, "MODEL_OUTPUT_INVALID", false),
    TASK_CANCELLED(HttpStatus.CONFLICT, "TASK_CANCELLED", false),
    TASK_DEADLINE(HttpStatus.CONFLICT, "TASK_DEADLINE", false),
    QUOTA_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "QUOTA_EXCEEDED", false),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", true),
    NOT_READY(HttpStatus.SERVICE_UNAVAILABLE, "NOT_READY", true),
    UPSTREAM_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "UPSTREAM_TIMEOUT", true),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", false);

    private final HttpStatus status;
    private final String code;
    private final boolean retryable;

    ErrorCode(HttpStatus status, String code, boolean retryable) {
        this.status = status;
        this.code = code;
        this.retryable = retryable;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }
}
