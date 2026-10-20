package com.codereview.agent.prompt;

/**
 * 一条有序消息。角色只允许 system／user，工具协议由 P33 单独扩展。
 */
public record PromptMessage(String role, String content) {

    public PromptMessage {
        if (role == null || (!"system".equals(role) && !"user".equals(role))) {
            throw new IllegalArgumentException("不支持的消息角色：" + role);
        }
        if (content == null) {
            throw new IllegalArgumentException("消息正文不能为空");
        }
    }
}
