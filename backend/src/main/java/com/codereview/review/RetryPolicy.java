package com.codereview.review;

import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.net.SocketTimeoutException;

/**
 * 单元级重试策略：指数退避 base→2×base→4×base；仅对可重试错误（网络/超时/5xx/限流 403·429）重试，
 * 确定性错误（无 key、切分失败、非 JSON）直接失败，不浪费重试。
 *
 * <p>退避基数由调用方传入（`review.retry-base-millis`）：早先这里写死 1000ms，
 * 而配置项 `retryBaseMillis` 全仓无人读取 —— 示例配置写着"指数退避基数"却改不动，
 * 属于会误导现场调参的死配置。
 */
public final class RetryPolicy {

    /** 追溯 cause 链的最大深度，防异常链自引用导致死循环 */
    private static final int MAX_CAUSE_DEPTH = 10;

    private RetryPolicy() {
    }

    /** 第 attempt 次重试前的退避时长（attempt=0 → base，base 无效时回落到 1000ms）。 */
    public static long backoffMillis(int attempt, long baseMillis) {
        long base = baseMillis > 0 ? baseMillis : 1000L;
        return (1L << Math.max(0, attempt)) * base;
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
