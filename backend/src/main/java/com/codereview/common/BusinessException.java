package com.codereview.common;

import lombok.Getter;

/**
 * 业务异常：code 供程序判断，message 直接透出给用户
 */
@Getter
public class BusinessException extends RuntimeException {
    private final int code;

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    public BusinessException(ResultCode rc) {
        super(rc.getMessage());
        this.code = rc.getCode();
    }
}
