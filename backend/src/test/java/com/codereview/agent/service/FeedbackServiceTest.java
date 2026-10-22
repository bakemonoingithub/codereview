package com.codereview.agent.service;

import com.codereview.agent.api.ApiException;
import com.codereview.agent.api.ErrorCode;
import com.codereview.agent.db.AgentDatabase;
import com.codereview.agent.dto.FeedbackDtos;
import com.codereview.agent.entity.AgentFeedback;
import com.codereview.agent.entity.AgentIssue;
import com.codereview.agent.entity.AgentReviewResult;
import com.codereview.agent.entity.AgentReviewTask;
import com.codereview.agent.mapper.AgentFeedbackMapper;
import com.codereview.agent.mapper.AgentIssueMapper;
import com.codereview.agent.mapper.AgentReviewResultMapper;
import com.codereview.agent.support.AgentIds;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 反馈主体完整性、条件写入与 revision 冲突。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FeedbackServiceTest {

    private static final String TASK_ID = AgentIds.next();
    private static final String RESULT_ID = AgentIds.next();
    private static final String ISSUE_ID = AgentIds.next();

    @Mock
    private AgentDatabase db;
    @Mock
    private AgentFeedbackMapper feedbackMapper;
    @Mock
    private AgentIssueMapper issueMapper;
    @Mock
    private AgentReviewResultMapper resultMapper;

    private FeedbackService service;

    @BeforeEach
    void setUp() {
        for (Class<?> entity : List.of(AgentFeedback.class, AgentIssue.class,
                AgentReviewResult.class, AgentReviewTask.class)) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), entity);
        }
        service = new FeedbackService(db);
        when(db.mapper(AgentFeedbackMapper.class)).thenReturn(feedbackMapper);
        when(db.mapper(AgentIssueMapper.class)).thenReturn(issueMapper);
        when(db.mapper(AgentReviewResultMapper.class)).thenReturn(resultMapper);
    }

    @Test
    void readingWithoutARecordReturnsEmptyValuesAndRevisionZero() {
        givenIssueSubject();
        when(feedbackMapper.selectOne(any())).thenReturn(null);

        FeedbackDtos.View view = service.get("ISSUE", ISSUE_ID);

        assertEquals("ISSUE", view.subjectKind());
        assertEquals(ISSUE_ID, view.subjectId());
        assertNull(view.assessment());
        assertNull(view.comment());
        assertEquals(0, view.revision());
        assertNull(view.updatedAt());
    }

    @Test
    void firstSaveCreatesRevisionOneAndStoresBlankAssessmentAsCleared() {
        givenIssueSubject();
        when(feedbackMapper.selectOne(any())).thenReturn(null);
        when(feedbackMapper.insert(any(AgentFeedback.class))).thenAnswer(call -> {
            AgentFeedback row = call.getArgument(0);
            row.setRevision(1);
            return 1;
        });

        FeedbackDtos.View view = service.save("ISSUE", ISSUE_ID,
                new FeedbackDtos.Update("ACCURATE", "  重复扣款已复现  ", 0));

        assertEquals("ACCURATE", view.assessment());
        assertEquals("  重复扣款已复现  ", view.comment());
        assertEquals(1, view.revision());
        ArgumentCaptor<AgentFeedback> inserted = ArgumentCaptor.forClass(AgentFeedback.class);
        verify(feedbackMapper).insert(inserted.capture());
        assertEquals(ISSUE_ID, inserted.getValue().getSubjectId());
        assertEquals("ISSUE", inserted.getValue().getSubjectKind());
        assertEquals("ACCURATE", inserted.getValue().getAssessment());
    }

    @Test
    void saveTreatsBlankAssessmentAsClearForTheReturnedView() {
        givenIssueSubject();
        when(feedbackMapper.selectOne(any())).thenReturn(null);
        when(feedbackMapper.insert(any(AgentFeedback.class))).thenAnswer(call -> {
            AgentFeedback row = call.getArgument(0);
            row.setRevision(1);
            return 1;
        });

        FeedbackDtos.View view = service.save("ISSUE", ISSUE_ID,
                new FeedbackDtos.Update("   ", "仅评论", 0));

        assertNull(view.assessment());
        assertEquals("仅评论", view.comment());
    }

    @Test
    void staleRevisionIsRejectedWithTheCurrentVersionForManualMerge() {
        givenIssueSubject();
        AgentFeedback current = feedback("ACCURATE", "其他页面的评论", 3);
        when(feedbackMapper.selectOne(any())).thenReturn(current);

        ApiException error = assertThrows(ApiException.class, () -> service.save("ISSUE", ISSUE_ID,
                new FeedbackDtos.Update("INACCURATE", "本地输入", 2)));

        assertEquals(ErrorCode.REVISION_CONFLICT, error.errorCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) error.details();
        FeedbackDtos.View latest = (FeedbackDtos.View) details.get("current");
        assertEquals(3, latest.revision());
        assertEquals("ACCURATE", latest.assessment());
        assertEquals("其他页面的评论", latest.comment());
        verify(feedbackMapper, never()).update(any(), any());
    }

    @Test
    void updateUsesConditionalRevisionAndIncrementsIt() {
        givenIssueSubject();
        AgentFeedback current = feedback("ACCURATE", "旧评论", 2);
        AgentFeedback updated = feedback("INACCURATE", "新评论", 3);
        when(feedbackMapper.selectOne(any())).thenReturn(current, updated);
        when(feedbackMapper.update(isNull(), any())).thenReturn(1);

        FeedbackDtos.View view = service.save("ISSUE", ISSUE_ID,
                new FeedbackDtos.Update("INACCURATE", "新评论", 2));

        assertEquals(3, view.revision());
        assertEquals("INACCURATE", view.assessment());
        verify(feedbackMapper).update(isNull(), any());
    }

    @Test
    void updateThatLosesTheRaceIsReportedAsConflict() {
        givenIssueSubject();
        AgentFeedback current = feedback("ACCURATE", null, 2);
        when(feedbackMapper.selectOne(any())).thenReturn(current);
        when(feedbackMapper.update(isNull(), any())).thenReturn(0);

        ApiException error = assertThrows(ApiException.class, () -> service.save("ISSUE", ISSUE_ID,
                new FeedbackDtos.Update("ACCURATE", null, 2)));

        assertEquals(ErrorCode.REVISION_CONFLICT, error.errorCode());
    }

    @Test
    void issueFeedbackRequiresACompleteIssueResult() {
        AgentIssue issue = new AgentIssue();
        issue.setId(ISSUE_ID);
        issue.setResultId(RESULT_ID);
        when(issueMapper.selectById(ISSUE_ID)).thenReturn(issue);
        AgentReviewResult incomplete = result("ISSUE", "INCOMPLETE");
        when(resultMapper.selectById(RESULT_ID)).thenReturn(incomplete);

        ApiException error = assertThrows(ApiException.class, () -> service.get("ISSUE", ISSUE_ID));

        assertEquals(ErrorCode.CONFLICT, error.errorCode());
        verify(feedbackMapper, never()).selectOne(any());
    }

    @Test
    void resultFeedbackOnlyAcceptsCompletePatternOrCouplingResults() {
        when(resultMapper.selectById(RESULT_ID)).thenReturn(result("ISSUE", "COMPLETE"));

        ApiException wrongKind = assertThrows(ApiException.class,
                () -> service.get("RESULT", RESULT_ID));
        assertEquals(ErrorCode.CONFLICT, wrongKind.errorCode());

        when(resultMapper.selectById(RESULT_ID)).thenReturn(result("PATTERN", "INCOMPLETE"));
        ApiException incomplete = assertThrows(ApiException.class,
                () -> service.get("RESULT", RESULT_ID));
        assertEquals(ErrorCode.CONFLICT, incomplete.errorCode());

        when(resultMapper.selectById(RESULT_ID)).thenReturn(result("COUPLING", "COMPLETE"));
        when(feedbackMapper.selectOne(any())).thenReturn(null);
        assertEquals("RESULT", service.get("RESULT", RESULT_ID).subjectKind());
    }

    @Test
    void invalidSubjectKindOrIdIsABadRequest() {
        ApiException kind = assertThrows(ApiException.class, () -> service.get("OTHER", RESULT_ID));
        assertEquals(ErrorCode.VALIDATION_FAILED, kind.errorCode());

        ApiException id = assertThrows(ApiException.class, () -> service.get("ISSUE", "not-a-ulid"));
        assertEquals(ErrorCode.VALIDATION_FAILED, id.errorCode());
    }

    @Test
    void missingExpectedRevisionIsABadRequest() {
        givenIssueSubject();

        ApiException error = assertThrows(ApiException.class,
                () -> service.save("ISSUE", ISSUE_ID, new FeedbackDtos.Update("ACCURATE", null, null)));

        assertEquals(ErrorCode.VALIDATION_FAILED, error.errorCode());
        assertTrue(error.getMessage().contains("expectedRevision"));
    }

    @Test
    void successfulUpdateReturnsItsOwnValuesEvenWhenAnotherPageWritesImmediatelyAfterward() {
        givenIssueSubject();
        when(feedbackMapper.selectOne(any())).thenReturn(
                feedback("ACCURATE", "旧评论", 2), feedback("UNKNOWN", "另一页面随后保存", 4));
        when(feedbackMapper.update(isNull(), any())).thenReturn(1);

        FeedbackDtos.View saved = service.save("ISSUE", ISSUE_ID,
                new FeedbackDtos.Update("INACCURATE", "本次保存", 2));

        assertEquals(3, saved.revision());
        assertEquals("INACCURATE", saved.assessment());
        assertEquals("本次保存", saved.comment());
        verify(feedbackMapper, org.mockito.Mockito.times(1)).selectOne(any());
    }

    @Test
    void simultaneousFirstInsertReturnsConflictWithWinningRevision() {
        givenIssueSubject();
        when(feedbackMapper.selectOne(any())).thenReturn(null, feedback("UNKNOWN", "先保存者", 1));
        when(feedbackMapper.insert(any(AgentFeedback.class)))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("same subject"));

        ApiException error = assertThrows(ApiException.class, () -> service.save("ISSUE", ISSUE_ID,
                new FeedbackDtos.Update("ACCURATE", "后保存者", 0)));

        assertEquals(ErrorCode.REVISION_CONFLICT, error.errorCode());
        FeedbackDtos.View latest = (FeedbackDtos.View) ((Map<?, ?>) error.details()).get("current");
        assertEquals(1, latest.revision());
        assertEquals("先保存者", latest.comment());
    }

    private void givenIssueSubject() {
        AgentIssue issue = new AgentIssue();
        issue.setId(ISSUE_ID);
        issue.setResultId(RESULT_ID);
        when(issueMapper.selectById(ISSUE_ID)).thenReturn(issue);
        when(resultMapper.selectById(RESULT_ID)).thenReturn(result("ISSUE", "COMPLETE"));
    }

    private static AgentReviewResult result(String kind, String completeness) {
        AgentReviewResult row = new AgentReviewResult();
        row.setId(RESULT_ID);
        row.setTaskId(TASK_ID);
        row.setKind(kind);
        row.setCompleteness(completeness);
        return row;
    }

    private static AgentFeedback feedback(String assessment, String comment, int revision) {
        AgentFeedback row = new AgentFeedback();
        row.setId(AgentIds.next());
        row.setSubjectKind("ISSUE");
        row.setSubjectId(ISSUE_ID);
        row.setAssessment(assessment);
        row.setComment(comment);
        row.setRevision(revision);
        return row;
    }
}
