package com.codereview.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

/** 提示词 v2 请求与视图；正文只经版本写入，元数据更新单独一条。 */
public final class PromptDtos {

    private PromptDtos() {
    }

    public record Create(
            @NotBlank @Size(max = 100) String name,
            @Size(max = 500) String description,
            List<@Size(max = 50) String> tags,
            @Pattern(regexp = "SPECIALIST|REPORT|AGENT", message = "不支持的用途")
            String purpose,
            @NotBlank String content) {
    }

    public record Update(
            @NotBlank @Size(max = 100) String name,
            @Size(max = 500) String description,
            List<@Size(max = 50) String> tags,
            @Pattern(regexp = "SPECIALIST|REPORT|AGENT", message = "不支持的用途")
            String purpose,
            @NotNull Integer expectedRevision) {
    }

    /** createNewVersion omitted by older callers means append, preserving the v2 default. */
    public record VersionCreate(@NotBlank String content, @NotNull Integer expectedRevision,
                                Boolean createNewVersion) {
        public VersionCreate(String content, Integer expectedRevision) {
            this(content, expectedRevision, null);
        }
    }

    public record VersionView(String id, int versionNo, String content, LocalDateTime createdAt) {
    }

    public record Summary(String id, String name, String purpose, List<String> tags, int revision,
                          LocalDateTime updatedAt) {
    }

    public record View(
            String id,
            String name,
            String description,
            List<String> tags,
            String purpose,
            VersionView currentVersion,
            int revision,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }
}
