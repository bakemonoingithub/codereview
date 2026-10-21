package com.codereview.agent.dto;

import com.fasterxml.jackson.databind.JsonNode;
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
            Boolean smartSplit,
            @Size(max = 200) String name) {
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

    public record View(String id, String name, String projectId, String route, String specialistType, String state,
                       String stopReason, String sourceKind, String branch, String headSha, String baseSha,
                       boolean smartSplit, int revision, LocalDateTime createdAt, LocalDateTime updatedAt,
                       LocalDateTime startedAt, LocalDateTime finishedAt, LocalDateTime deadlineAt,
                       LocalDateTime cancelRequestedAt, List<TargetView> targets, ConfigView config) {
    }

    public record Summary(String id, String name, String projectId, String route, String specialistType, String state,
                          String stopReason, LocalDateTime createdAt, LocalDateTime deadlineAt,
                          int targetCount, int succeededCount, int failedCount, int runningCount,
                          int queuedCount) {
    }

    /** 复制参数只给可编辑项；文件任务必须重新观察分支 HEAD，不承诺复用旧 SHA。 */
    public record CopyInput(String projectId, String route, String specialistType, String sourceKind,
                            String branch, String commitSha, String baseSha, List<TargetCopy> targets,
                            String strategyId, String modelConfigId, boolean smartSplit,
                            boolean reobserveHead, String name) {
    }

    public record TargetCopy(String path, String oldPath, String note) {
    }

    public record CallSummary(String id, String targetId, String unitId, String purpose, int attempt,
                              String state, String errorCode, LocalDateTime createdAt,
                              LocalDateTime startedAt, LocalDateTime finishedAt,
                              Integer generation, Integer unitOrdinal) {
    }

    public record CallDetail(String id, String part, String content, long length, String sha256,
                             boolean complete) {
    }

    /** 统一详情读取：结果正文按保存时的 JSON 返回，不按当前模板重建。 */
    public record ResultView(String id, String targetId, String kind, String schemaVersion,
                             String completeness, LocalDateTime createdAt, JsonNode body,
                             List<IssueView> issues, JsonNode provenance) {
    }

    public record IssueView(String id, int ordinal, String category, String severity, String title,
                            String reason, String suggestion, JsonNode locations,
                            JsonNode sourceUnits) {
    }

    public record MaterialView(String id, String path, String sha, String side, int startLine,
                               int endLine, String role, String disposition, String completeness,
                               String diagnostic, String contentHash, int byteSize, boolean sent) {
    }

    public record MaterialDetail(MaterialView metadata, String content, boolean complete) {
    }

    public record CallDetailFull(String id, String targetId, String unitId, String purpose, int attempt,
                                 String state, String errorCode, String errorMessage,
                                 String requestBody, String responseBody, String usageJson,
                                 LocalDateTime createdAt, LocalDateTime startedAt,
                                 LocalDateTime finishedAt) {
    }

    /**
     * 单个反馈对象的计数。准确率只统计明确判定项；分母为 0 时 accuracyPercent 为 null，
     * 前端必须显示"暂无明确判定"而不是 0% 或 100%。
     */
    public record FeedbackCounts(int total, int accurate, int inaccurate, int unknown, int unassessed,
                                 Integer accuracyPercent) {
    }

    /**
     * 任务级统计。问题级与结果级分开聚合，不合成一个跨对象的准确率；
     * 只统计本任务已完整发布的结果。
     */
    public record Statistics(String taskId, String specialistType,
                             FeedbackCounts issues, FeedbackCounts results) {
    }
}
