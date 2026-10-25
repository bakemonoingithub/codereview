package com.codereview.agent.api;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.http.converter.HttpMessageNotReadableException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V2ExceptionHandlerTest {

    @Test
    void conflictMapsToHttp409WithStableCode() {
        ResponseEntity<ApiErrorBody> response = V2ExceptionHandler.response(
                ErrorCode.CONFIG_IN_USE, "仍被引用", java.util.Map.of("strategies", 2));

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("CONFIG_IN_USE", response.getBody().code());
        assertFalse(response.getBody().retryable());
        assertNotNull(response.getBody().details());
    }

    @Test
    void rateLimitIsMarkedRetryableAndUses429() {
        ResponseEntity<ApiErrorBody> response = V2ExceptionHandler.response(
                ErrorCode.RATE_LIMITED, "上游限流", null);

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatusCode());
        assertTrue(response.getBody().retryable());
    }

    @Test
    void validationFailureIsNotRetryable() {
        ResponseEntity<ApiErrorBody> response = V2ExceptionHandler.response(
                ErrorCode.VALIDATION_FAILED, "参数校验失败", null);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertFalse(response.getBody().retryable());
        assertEquals("VALIDATION_FAILED", response.getBody().code());
    }

    @Test
    void retiredStrategyInputGetsSpecificMigrationHint() {
        var exception = new HttpMessageNotReadableException("invalid request",
                new IllegalArgumentException("strategyId 已退出，新任务请直接选择模型和通用规则"),
                new MockHttpInputMessage(new byte[0]));

        ResponseEntity<ApiErrorBody> response = new V2ExceptionHandler().handleUnreadable(exception);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(response.getBody().message().contains("modelConfigId"));
    }
}
