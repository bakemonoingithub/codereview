package com.codereview.agent.api;

import com.codereview.common.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * v2 统一错误处理。
 *
 * <p>只作用于 {@code com.codereview.agent.api} 下的控制器，旧接口继续走
 * {@code GlobalExceptionHandler}。顺序调高，避免旧兜底把 v2 异常也包成 200 信封。
 */
@RestControllerAdvice(basePackages = "com.codereview.agent.api")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class V2ExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(V2ExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiErrorBody> handleApi(ApiException e) {
        return response(e.errorCode(), e.getMessage(), e.details());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorBody> handleValidation(MethodArgumentNotValidException e) {
        Map<String, String> fields = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors()
                .forEach(error -> fields.putIfAbsent(error.getField(), error.getDefaultMessage()));
        return response(ErrorCode.VALIDATION_FAILED, "参数校验失败", Map.of("fields", fields));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorBody> handleIllegalArgument(IllegalArgumentException e) {
        return response(ErrorCode.VALIDATION_FAILED, e.getMessage(), null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorBody> handleOther(Exception e) {
        log.error("v2 未处理异常", e);
        return response(ErrorCode.INTERNAL_ERROR, "服务内部错误", null);
    }

    static ResponseEntity<ApiErrorBody> response(ErrorCode code, String message, Object details) {
        ApiErrorBody body = new ApiErrorBody(code.code(), message, code.retryable(), TraceContext.getTraceId(), details);
        return ResponseEntity.status(code.status()).body(body);
    }
}
