package com.codereview.agent.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
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
import com.codereview.agent.mapper.AgentReviewTaskMapper;
import com.codereview.agent.support.AgentIds;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 任务级统计只聚合完整结果，并分开问题级与结果级。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TaskStatisticsServiceTest {

    private static final String TASK_ID = AgentIds.next();
    private static final String R1 = AgentIds.next();
    private static final String R2 = AgentIds.next();
    private static final String R3 = AgentIds.next();
    private static final String R4 = AgentIds.next();

    @Mock
    private AgentDatabase db;
    @Mock
    private AgentReviewTaskMapper taskMapper;
    @Mock
    private AgentReviewResultMapper resultMapper;
    @Mock
    private AgentIssueMapper issueMapper;
    @Mock
    private AgentFeedbackMapper feedbackMapper;

    private TaskStatisticsService service;

    @BeforeEach
    void setUp() {
        for (Class<?> entity : List.of(AgentReviewResult.class, AgentIssue.class,
                AgentFeedback.class, AgentReviewTask.class)) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), entity);
        }
        when(db.mapper(AgentReviewTaskMapper.class)).thenReturn(taskMapper);
        when(db.mapper(AgentReviewResultMapper.class)).thenReturn(resultMapper);
        when(db.mapper(AgentIssueMapper.class)).thenReturn(issueMapper);
        when(db.mapper(AgentFeedbackMapper.class)).thenReturn(feedbackMapper);
        service = new TaskStatisticsService(db);
    }

    @Test
    void countsIssuesAndResultsSeparatelyAndOnlyForCompleteResults() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(task("ISSUE"));
        when(resultMapper.selectList(any())).thenReturn(List.of(
                result(R1, "ISSUE", "COMPLETE"),
                result(R2, "ISSUE", "COMPLETE"),
                result(R3, "ISSUE", "INCOMPLETE"),
                result(R4, "PATTERN", "COMPLETE")));
        when(issueMapper.selectList(any())).thenReturn(List.of(issue("I1", R1), issue("I2", R2)));
        when(feedbackMapper.selectList(any())).thenReturn(
                List.of(feedback("I1", "ISSUE", "ACCURATE"), feedback("I2", "ISSUE", "INACCURATE")),
                List.of(feedback(R4, "RESULT", "UNKNOWN")));

        TaskDtos.Statistics stats = service.statistics(TASK_ID);

        assertEquals(TASK_ID, stats.taskId());
        assertEquals("ISSUE", stats.specialistType());
        assertEquals(2, stats.issues().total());
        assertEquals(1, stats.issues().accurate());
        assertEquals(1, stats.issues().inaccurate());
        assertEquals(0, stats.issues().unknown());
        assertEquals(0, stats.issues().unassessed());
        assertEquals(50, stats.issues().accuracyPercent());

        assertEquals(1, stats.results().total());
        assertEquals(0, stats.results().accurate());
        assertEquals(1, stats.results().unknown());
        assertNull(stats.results().accuracyPercent());
    }

    @Test
    void unassessedAndCommentOnlySubjectsAreNotCountedAsAccurate() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(task("ISSUE"));
        when(resultMapper.selectList(any())).thenReturn(List.of(result(R1, "ISSUE", "COMPLETE")));
        when(issueMapper.selectList(any())).thenReturn(
                List.of(issue("I1", R1), issue("I2", R1), issue("I3", R1)));
        // I2 只有评论、没有评价；I3 完全没有反馈记录。
        when(feedbackMapper.selectList(any())).thenReturn(
                List.of(feedback("I1", "ISSUE", "ACCURATE"), feedback("I2", "ISSUE", null)),
                List.of());

        TaskDtos.Statistics stats = service.statistics(TASK_ID);

        assertEquals(3, stats.issues().total());
        assertEquals(1, stats.issues().accurate());
        assertEquals(2, stats.issues().unassessed());
        assertEquals(100, stats.issues().accuracyPercent());
    }

    @Test
    void roundsAccuracyWithoutFabricatingADenominator() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(task("ISSUE"));
        when(resultMapper.selectList(any())).thenReturn(List.of(result(R1, "ISSUE", "COMPLETE")));
        when(issueMapper.selectList(any())).thenReturn(
                List.of(issue("I1", R1), issue("I2", R1), issue("I3", R1)));
        when(feedbackMapper.selectList(any())).thenReturn(
                List.of(feedback("I1", "ISSUE", "ACCURATE"), feedback("I2", "ISSUE", "ACCURATE"),
                        feedback("I3", "ISSUE", "INACCURATE")),
                List.of());

        assertEquals(67, service.statistics(TASK_ID).issues().accuracyPercent());
    }

    @Test
    void taskWithoutCompleteResultsHasNoDenominator() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(task("PATTERN"));
        when(resultMapper.selectList(any())).thenReturn(List.of(result(R1, "PATTERN", "INCOMPLETE")));

        TaskDtos.Statistics stats = service.statistics(TASK_ID);

        assertEquals(0, stats.issues().total());
        assertEquals(0, stats.results().total());
        assertNull(stats.issues().accuracyPercent());
        assertNull(stats.results().accuracyPercent());
        verify(issueMapper, never()).selectList(any());
        verify(feedbackMapper, never()).selectList(any());
    }

    @Test
    void unknownTaskIsNotFound() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(null);

        ApiException error = assertThrows(ApiException.class, () -> service.statistics(TASK_ID));

        assertEquals(ErrorCode.NOT_FOUND, error.errorCode());
    }

    private static AgentReviewTask task(String type) {
        AgentReviewTask row = new AgentReviewTask();
        row.setId(TASK_ID);
        row.setSpecialistType(type);
        return row;
    }

    private static AgentReviewResult result(String id, String kind, String completeness) {
        AgentReviewResult row = new AgentReviewResult();
        row.setId(id);
        row.setTaskId(TASK_ID);
        row.setKind(kind);
        row.setCompleteness(completeness);
        return row;
    }

    private static AgentIssue issue(String id, String resultId) {
        AgentIssue row = new AgentIssue();
        row.setId(id);
        row.setResultId(resultId);
        return row;
    }

    private static AgentFeedback feedback(String subjectId, String kind, String assessment) {
        AgentFeedback row = new AgentFeedback();
        row.setSubjectId(subjectId);
        row.setSubjectKind(kind);
        row.setAssessment(assessment);
        return row;
    }
}
