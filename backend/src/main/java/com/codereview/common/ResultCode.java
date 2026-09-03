package com.codereview.common;

import lombok.Getter;

/**
 * 错误码（4 位数字分段）
 * 0 成功；1xxx 通用；2xxx 提示词；3xxx 模型；4xxx 策略；5xxx 项目；6xxx 审查记录；7xxx 报告
 */
@Getter
public enum ResultCode {
    SUCCESS(0, "success"),

    SYSTEM_ERROR(1000, "系统错误"),
    PARAM_ERROR(1001, "参数校验失败"),
    UNAUTHORIZED(1002, "未登录"),
    FORBIDDEN(1003, "无权限"),
    NOT_FOUND(1004, "资源不存在"),
    BUSINESS_ERROR(1005, "业务失败"),

    PROMPT_NOT_FOUND(2001, "提示词不存在"),
    PROMPT_VERSION_CONFLICT(2002, "版本冲突"),
    MODEL_NOT_FOUND(3001, "模型不存在"),
    MODEL_VERIFY_FAILED(3002, "连通验证失败"),
    STRATEGY_NOT_FOUND(4001, "策略不存在"),
    ANALYZER_TYPE_UNSUPPORTED(4002, "分析器类型不支持"),
    PROJECT_NOT_FOUND(5001, "项目不存在"),
    GIT_CONNECT_FAILED(5002, "仓库连通验证失败"),
    REVIEW_NOT_FOUND(6001, "审查记录不存在"),
    REVIEW_EXEC_FAILED(6002, "审查执行失败"),
    REPORT_NOT_FOUND(7001, "报告不存在"),
    REPORT_GEN_FAILED(7002, "报告生成失败");

    private final int code;
    private final String message;

    ResultCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
