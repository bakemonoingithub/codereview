package com.codereview.review;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetryPolicyTest {

    @Test
    void backoffExponential() {
        assertEquals(1000L, RetryPolicy.backoffMillis(0));
        assertEquals(2000L, RetryPolicy.backoffMillis(1));
        assertEquals(4000L, RetryPolicy.backoffMillis(2));
    }

    @Test
    void retryableErrors() {
        assertTrue(RetryPolicy.isRetryable(new ResourceAccessException("timeout")));
        assertTrue(RetryPolicy.isRetryable(new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR, "err")));
        assertTrue(RetryPolicy.isRetryable(new HttpClientErrorException(HttpStatus.TOO_MANY_REQUESTS, "rate")));
        assertTrue(RetryPolicy.isRetryable(new HttpClientErrorException(HttpStatus.FORBIDDEN, "limit")));
    }

    @Test
    void nonRetryableErrors() {
        assertFalse(RetryPolicy.isRetryable(new IllegalStateException("未配置 token")));
        assertFalse(RetryPolicy.isRetryable(new IllegalStateException("LLM 响应解析失败")));
        assertFalse(RetryPolicy.isRetryable(new HttpClientErrorException(HttpStatus.BAD_REQUEST, "bad")));
    }
}
