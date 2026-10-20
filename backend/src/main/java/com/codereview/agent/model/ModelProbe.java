package com.codereview.agent.model;

/**
 * 模型探针。
 *
 * <p>只证明"地址可达、凭据被接受、返回 JSON"，不证明完整窗口容量或工具调用能力；
 * 界面上的验证范围按此口径展示。
 */
public interface ModelProbe {

    ModelProbeOutcome probe(ModelProbeRequest request);
}
