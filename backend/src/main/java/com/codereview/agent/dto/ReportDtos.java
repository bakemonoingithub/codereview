package com.codereview.agent.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 报告：候选结果、输入冻结、生成状态、多结果阅读与下载。 */
public final class ReportDtos {

    private ReportDtos() {
    }

    /** 可选材料：只包含已完整发布的结果，任务可以是部分完成或已取消。 */
    public record Candidate(String resultId, String taskId, String taskName, String targetId, String kind,
                            String projectId, String projectName, String taskRoute, String taskState,
                            String sourceKind, String path, String oldPath, String headSha,
                            String baseSha, String branch, LocalDateTime resultCreatedAt,
                            int issueCount, String answerStatus, int unresolvedCount,
                            Feedback feedback) {
    }

    /** 结果级当前反馈；问题级反馈随各自问题冻结。 */
    public record Feedback(String assessment, String comment, int revision, LocalDateTime updatedAt) {
    }

    public record FreezeRequest(
            @NotBlank(message = "报告标题不能为空") @Size(max = 200, message = "报告标题不能超过 200 个字符")
            String title,
            @NotEmpty(message = "至少选择一个审查结果")
            @Size(max = 200, message = "一次最多冻结 200 个审查结果")
            List<@NotBlank(message = "审查结果 ID 不能为空") String> resultIds) {
    }

    public record FrozenSource(int ordinal, String resultId, JsonNode snapshot) {
    }

    /** 冻结结果读取视图；snapshot 是生成时保存的完整来源，不随最新数据变化。 */
    public record FrozenInput(String reportId, String title, String state, LocalDateTime createdAt,
                              int sourceCount, List<FrozenSource> sources) {
    }

    /** 选择模型及可选报告规则并发起生成；生成材料已冻结，不在这里重新选材。 */
    public record GenerateRequest(
            @NotBlank(message = "请选择生成模型") @Size(max = 26, message = "模型 ID 不合法")
            String modelConfigId,
            @Size(max = 4000, message = "报告补充要求不能超过 4000 个字符") String instruction,
            @Size(max = 26, message = "提示词 ID 不合法") String promptId,
            @Size(max = 26, message = "提示词版本 ID 不合法") String promptVersionId) {
        public GenerateRequest(String modelConfigId, String instruction) {
            this(modelConfigId, instruction, null, null);
        }
    }

    /** 生成时冻结的模型信息；不含凭据。 */
    public record ModelView(String id, String name, String modelName, Integer contextWindow,
                            Integer maxOutput, String tokenizer, String protocol, Integer revision) {
    }

    /** 报告实际使用的提示词版本；正文来自生成请求时冻结的快照。 */
    public record PromptView(String id, String name, String versionId, Integer versionNo, String content) {
    }

    /** 来源摘要；完整快照按 ordinal 单独读取，列表不重复传输代码正文。 */
    public record SourceSummary(int ordinal, String resultId, String kind, String projectId,
                                String projectName, String taskId, String taskRoute, String path,
                                String headSha, String baseSha, String summary, int issueCount,
                                int accurate, int inaccurate, int unknown, int unassessed) {
    }

    public record CallSummary(String id, String purpose, int attempt, String state,
                              String errorCode, String errorMessage, LocalDateTime createdAt,
                              LocalDateTime startedAt, LocalDateTime finishedAt) {
    }

    public record ReportSummary(String id, String title, String state, String errorCode,
                                String errorMessage, String modelName, int sourceCount,
                                int taskCount, int versionCount, Map<String, Integer> kindCounts,
                                LocalDateTime createdAt, LocalDateTime updatedAt,
                                LocalDateTime startedAt, LocalDateTime finishedAt) {
    }

    /**
     * 报告详情。正文只在 SUCCEEDED 时返回；失败保留冻结来源与错误，
     * 不把程序事实当成已完成报告。
     */
    public record ReportDetail(String id, String title, String state, String body,
                               String errorCode, String errorMessage, ModelView model, PromptView prompt,
                               String instruction, int sourceCount, int taskCount, int versionCount,
                               Map<String, Integer> kindCounts, List<SourceSummary> sources,
                               List<CallSummary> calls, LocalDateTime createdAt,
                               LocalDateTime updatedAt, LocalDateTime startedAt,
                               LocalDateTime finishedAt) {
    }
}
