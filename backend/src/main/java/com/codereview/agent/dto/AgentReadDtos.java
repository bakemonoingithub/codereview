package com.codereview.agent.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.util.List;

/** Agent 进度、工具调用与结果的读取模型；只返回本任务数据。 */
public final class AgentReadDtos {

    private AgentReadDtos() {
    }

    /** 进度：动作、轮次／尝试计数与已查覆盖；{@code limits} 来自任务冻结快照。 */
    public record Progress(String taskId, String state, String stopReason, int turnNo,
                           int toolAttempts, int networkAttempts, String currentAction,
                           JsonNode covered, JsonNode limits, LocalDateTime updatedAt,
                           Long elapsedSeconds, OutputUsage outputUsage) {
    }

    /** 输出用量只统计提供方实际返回的计数；每轮上限应与峰值比较。 */
    public record OutputUsage(Long totalOutputTokens, Long peakOutputTokens,
                              int reportedCalls, int unreportedCalls) {
    }

    public record ToolCallSummary(String id, int turnNo, int ordinal, String toolName,
                                  String status, LocalDateTime startedAt, LocalDateTime finishedAt) {
    }

    /** 单次工具调用：参数与 envelope 原样返回，列表页不放大正文。 */
    public record ToolCallDetail(String id, int turnNo, int ordinal, String toolName,
                                 String providerToolCallId, String arguments, JsonNode envelope,
                                 List<String> materialIds, String status,
                                 LocalDateTime startedAt, LocalDateTime finishedAt) {
    }

    /** 已发布的 Agent 结果；正文、引用与来源元数据在同一个冻结对象里。 */
    public record Result(String id, String schemaVersion, String completeness, LocalDateTime createdAt,
                         JsonNode body, JsonNode provenance) {
    }
}
