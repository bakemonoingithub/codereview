package com.codereview.agent.prompt;

import java.util.List;

/**
 * Agent 一轮组装的类型化输入。
 *
 * <p>只包含本次任务冻结的数据：要求、范围、固定版本、比较关系、可选指导提示词与旧结果线索。
 * 工具返回不在这里重复，它们已经作为 tool 消息进入历史；组装层只负责顺序与计量。
 */
public record AgentPromptInput(String requirements, String scopeKind, String branch, String headSha,
                               String description, List<String> paths, List<Version> versions,
                               List<Comparison> comparisons, String guidancePrompt,
                               String referenceBody) {

    public AgentPromptInput {
        paths = paths == null ? List.of() : List.copyOf(paths);
        versions = versions == null ? List.of() : List.copyOf(versions);
        comparisons = comparisons == null ? List.of() : List.copyOf(comparisons);
    }

    /** 不透明版本标识与固定 SHA。 */
    public record Version(String version, String sha) {
    }

    /** 冻结的多提交比较关系；根提交的 base 为 null。 */
    public record Comparison(String id, String headSha, String baseSha) {
    }
}
