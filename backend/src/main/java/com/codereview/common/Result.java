package com.codereview.common;

import lombok.Data;

/**
 * 统一响应体 {code, message, data, traceId}
 * data 为 null 时因 Jackson NON_NULL 配置而省略
 */
@Data
public class Result<T> {
    private int code;
    private String message;
    private T data;
    private String traceId;

    public static <T> Result<T> ok() {
        return ok(null);
    }

    public static <T> Result<T> ok(T data) {
        Result<T> r = new Result<>();
        r.code = ResultCode.SUCCESS.getCode();
        r.message = "success";
        r.data = data;
        r.traceId = TraceContext.getTraceId();
        return r;
    }

    public static <T> Result<T> fail(int code, String message) {
        return fail(code, message, null);
    }

    public static <T> Result<T> fail(int code, String message, T data) {
        Result<T> r = new Result<>();
        r.code = code;
        r.message = message;
        r.data = data;
        r.traceId = TraceContext.getTraceId();
        return r;
    }

    public static <T> Result<T> fail(ResultCode rc) {
        return fail(rc.getCode(), rc.getMessage(), null);
    }
}
