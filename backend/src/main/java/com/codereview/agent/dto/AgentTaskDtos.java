package com.codereview.agent.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Agent 任务发起与复制参数。
 *
 * <p>与专项 DTO 分开：Agent 没有 specialistType、策略和逐文件目标，范围用
 * {@code TEXT / FILES / COMMITS} 表达；文件与提交互斥。这里只做字段级约束，
 * 版本、存在性与模型准在服务层校验。
 */
public final class AgentTaskDtos {

    private AgentTaskDtos() {
    }

    public record Create(
            @NotBlank @Size(max = 26) String projectId,
            @Size(max = 200) String name,
            @NotBlank @Size(max = 26) String modelConfigId,
            @Size(max = 26) String guidancePromptId,
            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Size(min = 26, max = 26) String guidancePromptVersionId,
            @NotNull @Valid Scope scope,
            @NotBlank @Size(max = 16000) String requirements,
            @Size(max = 26) String referenceResultId) {
        public Create(String projectId, String name, String modelConfigId, String guidancePromptId,
                      Scope scope, String requirements, String referenceResultId) {
            this(projectId, name, modelConfigId, guidancePromptId, null, scope, requirements, referenceResultId);
        }
    }

    public record Scope(
            @NotBlank @Pattern(regexp = "TEXT|FILES|COMMITS", message = "范围只能是 TEXT、FILES 或 COMMITS")
            String kind,
            @Size(max = 200) String branch,
            @Size(max = 40) String observedSha,
            @Size(max = 100) List<@NotBlank @Size(max = 512) String> paths,
            @Size(max = 20) List<@NotBlank @Size(max = 40) String> commits,
            @Size(max = 2000) String description) {

        public Scope {
            paths = paths == null ? List.of() : List.copyOf(paths);
            commits = commits == null ? List.of() : List.copyOf(commits);
        }
    }

    /** 创建成功返回公共任务 ID 与详情位置；异步执行，不等任务完成。 */
    public record Created(String id, String state, String route, String detailUrl) {
    }

    /** 复制参数：重新观察 HEAD，不继承旧 SHA、消息或额度。 */
    public record CopyInput(String projectId, String name, String modelConfigId,
                            String guidancePromptId, String guidancePromptVersionId, Scope scope, String requirements,
                            String referenceResultId, boolean reobserveHead) {
    }
}
