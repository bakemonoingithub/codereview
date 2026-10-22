package com.codereview.agent.execution;

/** 单文件执行结果；批量执行用它汇总任务状态，不把异常直接抛成整批失败。 */
public record SpecialistTargetOutcome(String targetId, String state, String errorCode) {
}
