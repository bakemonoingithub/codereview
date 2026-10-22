package com.codereview.agent.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/** 项目 v2 请求与视图；凭据只入不出，视图只给"是否已配置"。 */
public final class ProjectDtos {

    private ProjectDtos() {
    }

    public record Create(
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Size(max = 500) String repoUrl,
            @Size(max = 1024) String credential,
            @Min(1) @Max(2) Integer credentialType,
            @Size(max = 200) String currentBranch) {
    }

    /** 仓库地址锁定；凭据留空保留，新值验证后替换。 */
    public record Update(
            @NotBlank @Size(max = 100) String name,
            @Size(max = 1024) String credential,
            @NotNull Integer expectedRevision) {
    }

    public record View(
            String id,
            String name,
            String repoUrl,
            String currentBranch,
            boolean credentialConfigured,
            int revision,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }
}
