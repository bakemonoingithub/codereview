package com.codereview.agent.model;

/** 探针结果；{@code detail} 只放可展示的短原因，不含响应正文。 */
public record ModelProbeOutcome(boolean passed, String detail) {
}
