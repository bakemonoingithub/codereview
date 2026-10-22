package com.codereview.agent.tools;

/** Agent 协议错误：结果结构、引用或工具批次不合法；用于决定是否给出有限纠错轮次。 */
public class AgentProtocolException extends RuntimeException {

    private final String code;

    public AgentProtocolException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
