package com.codereview.review;

import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.net.SocketTimeoutException;

/**
 * 单元级重试策略：指数退避 1s→2s→4s；仅对可重试错误（网络/超时/5xx/限流 403·429）重试，
 * 确定性错误（无 key、切分失败、非 JSON）直接失败，不浪费重试。
 */
public final class RetryPolicy {

    /** 追溯 cause 链的最大深度，防异常链自引用导致死循环 */
    private static final int MAX_CAUSE_DEPTH = 10;

    private RetryPolicy() {
    }

    /** 第 attempt 次重试前的退避时长（attempt=0 → 1s）。 */
    public static long backoffMillis(int attempt) {
        return (1L << attempt) * 1000L;
    }

    public static boolean isRetryable(Throwable e) {
        if (e instanceof ResourceAccessException) {
            return true; // 建连失败/网络不可达/请求阶段超时
        }
        if (hasCause(e, SocketTimeoutException.class)) {
            // 读超时：Spring 在**读响应**阶段超时时抛的是包着 SocketTimeoutException 的
            // RestClientException（"Error while extracting response ..."），而**不是**
            // ResourceAccessException —— 后者只在请求执行阶段抛。
            // 漏掉这一条，会让"网关变慢"被当成永久性失败、连重试都不给，
            // 与"给 LLM 调用加超时"的初衷正好相反（实测见 LlmClientTimeoutTest）。
            return true;
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

    /** 沿 cause 链查找指定类型（含自身） */
    private static boolean hasCause(Throwable e, Class<? extends Throwable> type) {
        Throwable current = e;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (type.isInstance(current)) {
                return true;
            }
            Throwable next = current.getCause();
            if (next == current) {
                break; // 自引用
            }
            current = next;
        }
        return false;
    }
}
