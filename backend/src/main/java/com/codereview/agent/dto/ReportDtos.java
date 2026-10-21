package com.codereview.agent.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

/** 报告输入：候选结果读取与生成前冻结；本阶段不生成正文。 */
public final class ReportDtos {

    private ReportDtos() {
    }

    /** 可选材料：只包含已完整发布的结果，任务可以是部分完成或已取消。 */
    public record Candidate(String resultId, String taskId, String targetId, String kind,
                            String projectId, String projectName, String taskRoute, String taskState,
                            String sourceKind, String path, String oldPath, String headSha,
                            String baseSha, String branch, LocalDateTime resultCreatedAt,
                            int issueCount, Feedback feedback) {
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
}
