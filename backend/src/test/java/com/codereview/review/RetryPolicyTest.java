package com.codereview.review;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

import java.net.SocketTimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetryPolicyTest {

    @Test
    void backoffExponential() {
        assertEquals(1000L, RetryPolicy.backoffMillis(0, 1000));
        assertEquals(2000L, RetryPolicy.backoffMillis(1, 1000));
        assertEquals(4000L, RetryPolicy.backoffMillis(2, 1000));
    }

    @Test
    void backoffUsesTheConfiguredBase() {
        // review.retry-base-millis 必须真的生效（原先写死 1000ms，配置是死配置）
        assertEquals(500L, RetryPolicy.backoffMillis(0, 500));
        assertEquals(1000L, RetryPolicy.backoffMillis(1, 500));
        assertEquals(2000L, RetryPolicy.backoffMillis(2, 500));
    }

    @Test
    void backoffFallsBackWhenBaseIsNotPositive() {
        assertEquals(1000L, RetryPolicy.backoffMillis(0, 0));
        assertEquals(1000L, RetryPolicy.backoffMillis(0, -5));
    }

    @Test
    void retryableErrors() {
        assertTrue(RetryPolicy.isRetryable(new ResourceAccessException("timeout")));
        assertTrue(RetryPolicy.isRetryable(new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR, "err")));
        assertTrue(RetryPolicy.isRetryable(new HttpClientErrorException(HttpStatus.TOO_MANY_REQUESTS, "rate")));
        assertTrue(RetryPolicy.isRetryable(new HttpClientErrorException(HttpStatus.FORBIDDEN, "limit")));
    }

    /**
     * 回归用例（A 批 E2 实测发现）：**读超时不是 ResourceAccessException**。
     *
     * <p>Spring 在读响应阶段超时时，抛的是包着 {@code SocketTimeoutException} 的
     * {@code RestClientException}；只有请求执行阶段才抛 {@code ResourceAccessException}。
     * 若只认后者，"网关变慢"会被判成不可重试 ⇒ 加了超时反而不给重试。
     */
    @Test
    void readTimeoutIsRetryable() {
        Throwable wrapped = new RestClientException(
                "Error while extracting response for type [java.lang.String] and content type [application/octet-stream]",
                new SocketTimeoutException("Read timed out"));

        assertTrue(RetryPolicy.isRetryable(wrapped), "读超时应可重试");
        assertTrue(RetryPolicy.isRetryable(new SocketTimeoutException("Read timed out")), "裸超时也应可重试");
    }

    @Test
    void readTimeoutIsRetryableEvenWhenNestedDeeper() {
        Throwable deep = new RestClientException("outer",
                new IllegalStateException("middle", new SocketTimeoutException("Read timed out")));

        assertTrue(RetryPolicy.isRetryable(deep), "深层嵌套的超时也要能识别");
    }

    @Test
    void nonRetryableErrors() {
        assertFalse(RetryPolicy.isRetryable(new IllegalStateException("未配置 token")));
        assertFalse(RetryPolicy.isRetryable(new IllegalStateException("LLM 响应解析失败")));
        assertFalse(RetryPolicy.isRetryable(new HttpClientErrorException(HttpStatus.BAD_REQUEST, "bad")));
        // 确定性错误即使被包一层也不该变成可重试
        assertFalse(RetryPolicy.isRetryable(
                new RestClientException("parse fail", new IllegalStateException("不是 JSON"))));
    }

    @Test
    void causeChainSelfReferenceDoesNotHang() {
        Throwable selfReferencing = new IllegalStateException("self") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };

        assertFalse(RetryPolicy.isRetryable(selfReferencing), "异常链自引用不应死循环");
    }
}
