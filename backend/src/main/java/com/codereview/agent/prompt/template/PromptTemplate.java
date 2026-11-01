package com.codereview.agent.prompt.template;

import java.util.Set;

/**
 * 一份随应用发布的提示词模板。
 *
 * <p>{@code id} 只在清单、代码与测试之间使用，不进入模型正文；{@code version} 是模板自身
 * 的修订标识，也不注入正文。{@code variables} 必须是正文中实际出现的 {@code {{name}}}
 * 占位符集合，由加载时校验。
 *
 * @param id        稳定标识，如 {@code instruction.ISSUE_FILE}
 * @param kind      模板类别，如 {@code INSTRUCTION}、{@code SCHEMA}、{@code SYSTEM}
 * @param purpose   关联用途的 wire name；公共模板为 {@code null}
 * @param version   模板修订标识
 * @param path      classpath 相对路径
 * @param body      原始正文，包含全部空白与末尾换行，不做 strip
 * @param variables 允许的变量名
 */
public record PromptTemplate(String id, String kind, String purpose, String version,
                             String path, String body, Set<String> variables) {

    public PromptTemplate {
        variables = Set.copyOf(variables);
    }
}
