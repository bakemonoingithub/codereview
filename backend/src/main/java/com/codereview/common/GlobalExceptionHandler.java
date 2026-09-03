package com.codereview.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 全局异常处理：业务异常透出 code/message；参数校验返回 1001 + 字段错误列表；兜底 1000
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    public record FieldErrorItem(String field, String message) {
    }

    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusiness(BusinessException e) {
        return Result.fail(e.getCode(), e.getMessage());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public Result<List<FieldErrorItem>> handleValidation(BindException e) {
        List<FieldErrorItem> items = e.getBindingResult().getFieldErrors().stream()
                .map((FieldError fe) -> new FieldErrorItem(fe.getField(), fe.getDefaultMessage()))
                .collect(Collectors.toList());
        return Result.fail(ResultCode.PARAM_ERROR.getCode(), ResultCode.PARAM_ERROR.getMessage(), items);
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleOther(Exception e) {
        log.error("未处理异常", e);
        return Result.fail(ResultCode.SYSTEM_ERROR.getCode(), ResultCode.SYSTEM_ERROR.getMessage());
    }
}
