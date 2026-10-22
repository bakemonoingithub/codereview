package com.codereview.agent.invocation;

/** 发送前的保守容量估算；整文件专项可按授权路径仅作诊断。 */
public record CapacityEstimate(int inputEstimate, int outputReserve, int safetyReserve,
                               int contextWindow, String method) {

    public int required() {
        return inputEstimate + outputReserve + safetyReserve;
    }

    public boolean fits() {
        return required() <= contextWindow;
    }
}
