package com.codereview.review;

import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/**
 * 单元级重试策略：指数退避 1s→2s→4s；仅对可重试错误（网络/5xx/限流 403·429）重试，
 * 确定性错误（无 key、切分失败、非 JSON）直接失败，不浪费重试。
 */
public final class RetryPolicy {

    private RetryPolicy() {
    }

    /** 第 attempt 次重试前的退避时长（attempt=0 → 1s）。 */
    public static long backoffMillis(int attempt) {
        return (1L << attempt) * 1000L;
    }

    public static boolean isRetryable(Throwable e) {
        if (e instanceof ResourceAccessException) {
            return true; // 网络超时/连接失败
        }
        if (e instanceof HttpServerErrorException) {
            return true; // 5xx
        }
        if (e instanceof HttpClientErrorException hce) {
            int code = hce.getStatusCode().value();
            return code == 429 || code == 403; // 限流/频率限制
        }
        return false;
    }
}
