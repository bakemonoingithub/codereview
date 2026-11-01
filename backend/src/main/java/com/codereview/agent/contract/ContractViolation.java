package com.codereview.agent.contract;

/**
 * 输出契约检查发现的一处结构问题。
 *
 * <p>{@code code} 保留调用方原有的错误分类（例如专项的 {@code UNKNOWN_FIELD}、
 * {@code MISSING_FIELD}，Agent 提交的 {@code RESULT_INVALID}），{@code detail} 是面向
 * 模型的定位说明。这里只描述“哪条规则被违反”，不判断结论对错。
 */
public record ContractViolation(String code, String detail) {
}
