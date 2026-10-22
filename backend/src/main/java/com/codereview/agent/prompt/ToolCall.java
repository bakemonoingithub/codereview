package com.codereview.agent.prompt;

/**
 * 模型返回的一个原生工具调用。
 *
 * <p>{@code id} 是提供方生成的标识，必须原样回传给对应的工具消息；
 * {@code arguments} 保留模型返回的原始 JSON 文本，解析与校验由工具层负责。
 */
public record ToolCall(String id, String name, String arguments) {

    public ToolCall {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("工具调用缺少 ID");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("工具调用缺少名称");
        }
        arguments = arguments == null || arguments.isBlank() ? "{}" : arguments;
    }
}
