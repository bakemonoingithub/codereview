package com.codereview.agent.invocation;

/** 发送前的容量判断结果；任何一项超出都不得发请求。 */
public record CapacityEstimate(int inputEstimate, int outputReserve, int safetyReserve,
                               int contextWindow, String method) {

    public int required() {
        return inputEstimate + outputReserve + safetyReserve;
    }

    public boolean fits() {
        return required() <= contextWindow;
    }
}
