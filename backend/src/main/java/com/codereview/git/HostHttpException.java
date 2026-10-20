package com.codereview.git;

import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.ResourceAccessException;

/**
 * 宿主返回了 HTTP 错误或传输失败。
 *
 * <p>保留真实状态码，让读取层能区分 404（确实不存在）、401/403（凭据被拒）、
 * 429（限流）与 5xx（宿主不可用），而不是统一折成"仓库不可达"。
 * 超时与连接失败用 {@link #TIMEOUT} 表示，不伪造 HTTP 状态。
 */
public class HostHttpException extends RuntimeException {

    /** 传输层超时或连接失败，没有 HTTP 状态可引用。 */
    public static final int TIMEOUT = -1;

    private final int statusCode;
    private final String retryAfter;

    public HostHttpException(int statusCode, String message, String retryAfter, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        this.retryAfter = retryAfter;
    }

    public static HostHttpException from(RestClientResponseException e) {
        String retryAfter = e.getResponseHeaders() == null
                ? null : e.getResponseHeaders().getFirst("Retry-After");
        return new HostHttpException(e.getStatusCode().value(),
                "宿主返回 HTTP " + e.getStatusCode().value(), retryAfter, e);
    }

    public static HostHttpException transport(ResourceAccessException e) {
        return new HostHttpException(TIMEOUT, "连接宿主失败或超时", null, e);
    }

    public int statusCode() {
        return statusCode;
    }

    public String retryAfter() {
        return retryAfter;
    }

    public boolean timeout() {
        return statusCode == TIMEOUT;
    }
}
