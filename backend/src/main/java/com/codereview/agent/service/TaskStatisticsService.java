package com.codereview.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.codereview.agent.api.ApiException;
import com.codereview.agent.api.ErrorCode;
import com.codereview.agent.db.AgentDatabase;
import com.codereview.agent.dto.TaskDtos;
import com.codereview.agent.entity.AgentFeedback;
import com.codereview.agent.entity.AgentIssue;
import com.codereview.agent.entity.AgentReviewResult;
import com.codereview.agent.entity.AgentReviewTask;
import com.codereview.agent.mapper.AgentFeedbackMapper;
import com.codereview.agent.mapper.AgentIssueMapper;
import com.codereview.agent.mapper.AgentReviewResultMapper;
import com.codereview.agent.mapper.AgentReviewResultItemMapper;
import com.codereview.agent.entity.AgentReviewResultItem;
import com.codereview.agent.mapper.AgentReviewTaskMapper;
import com.codereview.agent.support.AgentIds;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务级人工判定统计。
 *
 * <p>问题级对象是完整问题结果内的问题，结果级对象是完整的模式／耦合结果；两者分别计数，
 * 不混算成一个准确率。只读稳定 ID 与当前反馈，不加载结果正文。分母为 0 时返回 null 百分比，
 * 由界面显示"暂无明确判定"。
 */
@Service
@ConditionalOnProperty(prefix = "agent.datasource", name = "url")
public class TaskStatisticsService {

    private static final String COMPLETE = "COMPLETE";

    private final AgentDatabase db;

    public TaskStatisticsService(AgentDatabase db) {
        this.db = db;
    }

    public TaskDtos.Statistics statistics(String taskId) {
        String id = AgentIds.requireValid(taskId, "任务 ID");
        AgentReviewTask task = db.mapper(AgentReviewTaskMapper.class).selectById(id);
        if (task == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, "任务不存在：" + taskId);
        }
        List<AgentReviewResult> results = db.mapper(AgentReviewResultMapper.class).selectList(
                new LambdaQueryWrapper<AgentReviewResult>()
                        .select(AgentReviewResult::getId, AgentReviewResult::getKind, AgentReviewResult::getCompleteness)
                        .eq(AgentReviewResult::getTaskId, id)
                        .eq(AgentReviewResult::getCompleteness, COMPLETE)
                        .orderByAsc(AgentReviewResult::getCreatedAt))
                .stream()
                // 条件已下推数据库；这里再按正式完整性过滤一次，避免未来查询调整把未完成结果算进分母。
                .filter(result -> COMPLETE.equals(result.getCompleteness()))
                .toList();
        List<String> issueResultIds = results.stream()
                .filter(result -> "ISSUE".equals(result.getKind()))
                .map(AgentReviewResult::getId)
                .toList();
        List<String> resultSubjectIds = results.stream()
                .filter(result -> "PATTERN".equals(result.getKind()) || "COUPLING".equals(result.getKind()))
                .map(AgentReviewResult::getId)
                .toList();

        List<String> issueIds = issueResultIds.isEmpty() ? List.of()
                : db.mapper(AgentIssueMapper.class).selectList(
                        new LambdaQueryWrapper<AgentIssue>()
                                .select(AgentIssue::getId)
                                .in(AgentIssue::getResultId, issueResultIds)
                                .orderByAsc(AgentIssue::getOrdinal))
                .stream().map(AgentIssue::getId).toList();

        Map<String, String> issueAssessments = assessments("ISSUE", issueIds);
        Map<String, String> resultAssessments = assessments("RESULT", resultSubjectIds);
        return new TaskDtos.Statistics(id, task.getSpecialistType(),
                counts(issueIds, issueAssessments), counts(resultSubjectIds, resultAssessments),
                itemCounts("PATTERN", results), itemCounts("COUPLING", results));
    }

    private TaskDtos.FeedbackCounts itemCounts(String kind, List<AgentReviewResult> results) {
        List<String> resultIds = results.stream().filter(result -> kind.equals(result.getKind()))
                .map(AgentReviewResult::getId).toList();
        List<String> ids = resultIds.isEmpty() ? List.of()
                : db.mapper(AgentReviewResultItemMapper.class).selectList(
                        new LambdaQueryWrapper<AgentReviewResultItem>()
                                .eq(AgentReviewResultItem::getKind, kind)
                                .in(AgentReviewResultItem::getResultId, resultIds))
                .stream().map(AgentReviewResultItem::getId).toList();
        return counts(ids, assessments(kind, ids));
    }

    private Map<String, String> assessments(String subjectKind, List<String> subjectIds) {
        Map<String, String> assessments = new LinkedHashMap<>();
        if (subjectIds.isEmpty()) {
            return assessments;
        }
        List<AgentFeedback> rows = db.mapper(AgentFeedbackMapper.class).selectList(
                new LambdaQueryWrapper<AgentFeedback>()
                        .select(AgentFeedback::getSubjectId, AgentFeedback::getAssessment)
                        .eq(AgentFeedback::getSubjectKind, subjectKind)
                        .in(AgentFeedback::getSubjectId, subjectIds));
        for (AgentFeedback row : rows) {
            assessments.put(row.getSubjectId(), row.getAssessment());
        }
        return assessments;
    }

    private static TaskDtos.FeedbackCounts counts(List<String> subjectIds, Map<String, String> assessments) {
        int accurate = 0;
        int inaccurate = 0;
        int unknown = 0;
        int unassessed = 0;
        for (String subjectId : subjectIds) {
            String assessment = assessments.get(subjectId);
            if ("ACCURATE".equals(assessment)) {
                accurate++;
            } else if ("INACCURATE".equals(assessment)) {
                inaccurate++;
            } else if ("UNKNOWN".equals(assessment)) {
                unknown++;
            } else {
                // 未反馈与只有评论（评价为空）都属于未判定，不能算作准确。
                unassessed++;
            }
        }
        int denominator = accurate + inaccurate;
        Integer percent = denominator == 0 ? null : Math.round(accurate * 100f / denominator);
        return new TaskDtos.FeedbackCounts(subjectIds.size(), accurate, inaccurate, unknown, unassessed, percent);
    }
}
