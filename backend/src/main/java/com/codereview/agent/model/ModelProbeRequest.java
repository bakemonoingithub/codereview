package com.codereview.agent.model;

/** 模型可达性探针的输入；凭据只在这里短暂出现，不落日志。 */
public record ModelProbeRequest(String baseUrl, String modelName, String credential) {
}
