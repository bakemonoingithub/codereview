package com.codereview.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/** 策略 v2 请求与视图；专项类型只允许三类，关联以稳定 ID 保存。 */
public final class StrategyDtos {

    private StrategyDtos() {
    }

    public record Create(
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Pattern(regexp = "ISSUE|PATTERN|COUPLING", message = "专项类型只能是 ISSUE/PATTERN/COUPLING")
            String specialistType,
            @Size(min = 26, max = 26) String modelConfigId,
            @Size(min = 26, max = 26) String promptId) {
    }

    public record Update(
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Pattern(regexp = "ISSUE|PATTERN|COUPLING", message = "专项类型只能是 ISSUE/PATTERN/COUPLING")
            String specialistType,
            @Size(min = 26, max = 26) String modelConfigId,
            @Size(min = 26, max = 26) String promptId,
            @NotNull Integer expectedRevision) {
    }

    public record View(
            String id,
            String name,
            String specialistType,
            String modelConfigId,
            String promptId,
            int revision,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }
}
