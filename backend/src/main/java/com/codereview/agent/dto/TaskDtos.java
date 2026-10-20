package com.codereview.agent.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

/** 任务 v2 请求与视图。任务成功不等于 HTTP 成功，异步创建返回 202。 */
public final class TaskDtos {

    private TaskDtos() {
    }

    public record Create(
            @NotBlank @Size(max = 26) String projectId,
            @Size(max = 20) String route,
            @NotBlank @Size(max = 20) String specialistType,
            @NotNull @Valid Source source,
            @NotEmpty @Size(max = 500) List<@Valid Target> targets,
            @Size(max = 26) String strategyId,
            @Size(max = 26) String modelConfigId,
            Boolean smartSplit) {
    }

    /** FILES 需要 branch；DIFF 需要完整 commitSha，baseSha 可空表示第一父提交。 */
    public record Source(
            @NotBlank @Size(max = 10) String kind,
            @Size(max = 200) String branch,
            @Size(max = 40) String observedSha,
            @Size(max = 40) String commitSha,
            @Size(max = 40) String baseSha) {
    }

    public record Target(
            @NotBlank @Size(max = 512) String path,
            @Size(max = 512) String oldPath,
            @Size(max = 4000) String note) {
    }

    public record Cancel(@NotNull Integer expectedRevision) {
    }

    public record TargetView(String id, int ordinal, String path, String oldPath, String baseSha,
                             String headSha, String note, String state, String errorCode, int revision,
                             LocalDateTime createdAt, LocalDateTime updatedAt) {
    }

    public record ConfigView(String modelConfigId, String modelName, Integer modelRevision,
                             String strategyId, String strategyName, Integer strategyRevision,
                             String promptId, Integer promptVersionNo) {
    }

    public record View(String id, String projectId, String route, String specialistType, String state,
                       String stopReason, String sourceKind, String branch, String headSha, String baseSha,
                       boolean smartSplit, int revision, LocalDateTime createdAt, LocalDateTime updatedAt,
                       LocalDateTime startedAt, LocalDateTime finishedAt, LocalDateTime deadlineAt,
                       LocalDateTime cancelRequestedAt, List<TargetView> targets, ConfigView config) {
    }

    public record Summary(String id, String projectId, String route, String specialistType, String state,
                          String stopReason, LocalDateTime createdAt, LocalDateTime deadlineAt,
                          int targetCount, int succeededCount, int failedCount, int runningCount,
                          int queuedCount) {
    }

    /** 复制参数只给可编辑项；文件任务必须重新观察分支 HEAD，不承诺复用旧 SHA。 */
    public record CopyInput(String projectId, String route, String specialistType, String sourceKind,
                            String branch, String commitSha, String baseSha, List<TargetCopy> targets,
                            String strategyId, String modelConfigId, boolean smartSplit,
                            boolean reobserveHead) {
    }

    public record TargetCopy(String path, String oldPath, String note) {
    }

    public record CallSummary(String id, String targetId, String unitId, String purpose, int attempt,
                              String state, String errorCode, LocalDateTime createdAt,
                              LocalDateTime startedAt, LocalDateTime finishedAt) {
    }

    public record CallDetail(String id, String part, String content, long length, String sha256,
                             boolean complete) {
    }
}
