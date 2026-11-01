package com.codereview.agent.tools.capability;

import java.util.List;

/**
 * 一个工具的中立能力定义：名称、用途说明与参数约束。
 *
 * <p>能力定义与模型协议无关，不包含 {@code type=function} 之类的请求字段；把能力定义
 * 转成当前模型协议 JSON 的职责在协议适配层。{@code submit_result} 与只读工具使用同一种
 * 定义结构，但只有只读工具进入参数解析器。
 */
public record ToolCapability(String name, String description, List<ToolParameter> parameters) {

    public ToolCapability {
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
    }

    /** 参数声明顺序即请求中的属性顺序，也与解析顺序一致。 */
    public List<String> parameterNames() {
        return parameters.stream().map(ToolParameter::name).toList();
    }

    /** 必填参数，按声明顺序。 */
    public List<String> requiredParameters() {
        return parameters.stream().filter(ToolParameter::required).map(ToolParameter::name).toList();
    }

    public ToolParameter parameter(String name) {
        return parameters.stream()
                .filter(parameter -> parameter.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("工具 " + this.name + " 没有参数：" + name));
    }
}
