package com.codereview.agent.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.codereview.agent.api.ApiException;
import com.codereview.agent.api.ErrorCode;
import com.codereview.agent.api.PageResponse;
import com.codereview.agent.db.AgentDatabase;
import com.codereview.agent.dto.ReportDtos;
import com.codereview.agent.entity.AgentFeedback;
import com.codereview.agent.entity.AgentIssue;
import com.codereview.agent.entity.AgentProject;
import com.codereview.agent.entity.AgentReport;
import com.codereview.agent.entity.AgentReportSource;
import com.codereview.agent.entity.AgentReviewResult;
import com.codereview.agent.entity.AgentReviewTask;
import com.codereview.agent.entity.AgentTaskTarget;
import com.codereview.agent.mapper.AgentFeedbackMapper;
import com.codereview.agent.mapper.AgentIssueMapper;
import com.codereview.agent.mapper.AgentProjectMapper;
import com.codereview.agent.mapper.AgentReportMapper;
import com.codereview.agent.mapper.AgentReportSourceMapper;
import com.codereview.agent.mapper.AgentReviewResultMapper;
import com.codereview.agent.mapper.AgentReviewTaskMapper;
import com.codereview.agent.mapper.AgentTaskTargetMapper;
import com.codereview.agent.support.AgentIds;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 报告候选只含完整结果；冻结保存正文、问题、版本与当时反馈。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReportInputServiceTest {

    private static final String TASK_1 = AgentIds.next();
    private static final String TASK_2 = AgentIds.next();
    private static final String TARGET_1 = AgentIds.next();
    private static final String RESULT_1 = AgentIds.next();
    private static final String RESULT_2 = AgentIds.next();
    private static final String ISSUE_1 = AgentIds.next();
    private static final String PROJECT_1 = AgentIds.next();
    private static final String REPORT_ID = AgentIds.next();

    private final ObjectMapper json = new ObjectMapper();

    @Mock
    private AgentDatabase db;
    @Mock
    private AgentReviewResultMapper resultMapper;
    @Mock
    private AgentReviewTaskMapper taskMapper;
    @Mock
    private AgentTaskTargetMapper targetMapper;
    @Mock
    private AgentProjectMapper projectMapper;
    @Mock
    private AgentIssueMapper issueMapper;
    @Mock
    private AgentFeedbackMapper feedbackMapper;
    @Mock
    private AgentReportMapper reportMapper;
    @Mock
    private AgentReportSourceMapper reportSourceMapper;

    private ReportInputService service;

    @BeforeEach
    void setUp() {
        for (Class<?> entity : List.of(AgentReviewResult.class, AgentReviewTask.class,
                AgentTaskTarget.class, AgentProject.class, AgentIssue.class, AgentFeedback.class,
                AgentReport.class, AgentReportSource.class)) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), entity);
        }
        when(db.mapper(AgentReviewResultMapper.class)).thenReturn(resultMapper);
        when(db.mapper(AgentReviewTaskMapper.class)).thenReturn(taskMapper);
        when(db.mapper(AgentTaskTargetMapper.class)).thenReturn(targetMapper);
        when(db.mapper(AgentProjectMapper.class)).thenReturn(projectMapper);
        when(db.mapper(AgentIssueMapper.class)).thenReturn(issueMapper);
        when(db.mapper(AgentFeedbackMapper.class)).thenReturn(feedbackMapper);
        when(db.mapper(AgentReportMapper.class)).thenReturn(reportMapper);
        when(db.mapper(AgentReportSourceMapper.class)).thenReturn(reportSourceMapper);
        when(db.inTransaction(any())).thenAnswer(invocation -> {
            Supplier<?> work = invocation.getArgument(0);
            return work.get();
        });
        service = new ReportInputService(db, json);
    }

    @Test
    void candidatesIncludeCompleteResultsFromPartialAndCancelledTasks() {
        when(resultMapper.selectList(any())).thenReturn(List.of(
                result(RESULT_1, TASK_1, TARGET_1, "ISSUE", "{\"summary\":\"问题\"}"),
                result(RESULT_2, TASK_2, null, "AGENT", "## Agent 结论")));
        when(taskMapper.selectList(any())).thenReturn(List.of(
                task(TASK_1, PROJECT_1, "PARTIAL", "FILES", "main"),
                task(TASK_2, PROJECT_1, "CANCELLED", "DIFF", null)));
        when(targetMapper.selectList(any())).thenReturn(List.of(target()));
        when(projectMapper.selectList(any())).thenReturn(List.of(project()));
        when(issueMapper.selectList(any())).thenReturn(List.of(issue()));
        when(feedbackMapper.selectList(any())).thenReturn(List.of());

        PageResponse<ReportDtos.Candidate> page = service.candidates(null, null, null, null, 20);

        assertEquals(2, page.items().size());
        ReportDtos.Candidate issue = page.items().get(0);
        assertEquals("ISSUE", issue.kind());
        assertEquals("A.java", issue.path());
        assertEquals("abc123", issue.headSha());
        assertEquals("订单服务", issue.projectName());
        assertEquals("PARTIAL", issue.taskState());
        assertEquals(1, issue.issueCount());
        assertNull(issue.feedback());

        ReportDtos.Candidate agent = page.items().get(1);
        assertEquals("AGENT", agent.kind());
        assertNull(agent.path());
        assertEquals("AGENT", agent.taskRoute());
        assertEquals("DIFF", agent.sourceKind());
        assertNull(agent.branch());
    }

    @Test
    void freezeSnapshotsBodyIssuesVersionAndFeedbackInRequestOrder() throws Exception {
        when(resultMapper.selectList(any())).thenReturn(List.of(
                result(RESULT_1, TASK_1, TARGET_1, "ISSUE",
                        "{\"summary\":\"订单重复扣款\",\"issues\":[{\"title\":\"重复扣款\"}]}"),
                result(RESULT_2, TASK_2, null, "PATTERN", "{\"summary\":\"策略模式\"}")));
        when(taskMapper.selectList(any())).thenReturn(List.of(
                task(TASK_1, PROJECT_1, "PARTIAL", "FILES", "main"),
                task(TASK_2, PROJECT_1, "SUCCEEDED", "FILES", "main")));
        when(targetMapper.selectList(any())).thenReturn(List.of(target()));
        when(projectMapper.selectList(any())).thenReturn(List.of(project()));
        when(issueMapper.selectList(any())).thenReturn(List.of(issue()));
        AgentFeedback issueFeedback = feedback(ISSUE_1, "ISSUE", "ACCURATE", "已复现");
        AgentFeedback resultFeedback = feedback(RESULT_2, "RESULT", "UNKNOWN", "需结合事务确认");
        when(feedbackMapper.selectList(any())).thenReturn(List.of(issueFeedback), List.of(resultFeedback));

        ReportDtos.FrozenInput frozen = service.freeze(
                new ReportDtos.FreezeRequest("  订单支付报告  ", List.of(RESULT_1, RESULT_2)));

        assertEquals("订单支付报告", frozen.title());
        assertEquals("INPUT_FROZEN", frozen.state());
        assertEquals(2, frozen.sourceCount());
        assertEquals(1, frozen.sources().get(0).ordinal());
        assertEquals(RESULT_1, frozen.sources().get(0).resultId());
        assertEquals(RESULT_2, frozen.sources().get(1).resultId());

        JsonNode first = frozen.sources().get(0).snapshot();
        assertEquals("订单重复扣款", first.path("result").path("body").path("summary").asText());
        assertEquals("A.java", first.path("target").path("path").asText());
        assertEquals("abc123", first.path("target").path("headSha").asText());
        assertEquals("订单服务", first.path("project").path("name").asText());
        assertEquals("PARTIAL", first.path("task").path("state").asText());
        assertEquals("ACCURATE", first.path("issues").get(0).path("feedback").path("assessment").asText());
        assertEquals("已复现", first.path("issues").get(0).path("feedback").path("comment").asText());

        JsonNode second = frozen.sources().get(1).snapshot();
        assertEquals("UNKNOWN", second.path("resultFeedback").path("assessment").asText());
        assertEquals("需结合事务确认", second.path("resultFeedback").path("comment").asText());

        ArgumentCaptor<AgentReport> report = ArgumentCaptor.forClass(AgentReport.class);
        verify(reportMapper).insert(report.capture());
        assertEquals("INPUT_FROZEN", report.getValue().getState());
        assertEquals(0, report.getValue().getRevision());
        JsonNode summary = json.readTree(report.getValue().getFrozenInput());
        assertEquals(2, summary.path("sourceCount").asInt());
        assertEquals(1, summary.path("kinds").path("ISSUE").asInt());
        assertEquals(1, summary.path("kinds").path("PATTERN").asInt());

        ArgumentCaptor<AgentReportSource> sources = ArgumentCaptor.forClass(AgentReportSource.class);
        verify(reportSourceMapper, org.mockito.Mockito.times(2)).insert(sources.capture());
        assertEquals(RESULT_1, sources.getAllValues().get(0).getResultId());
        assertEquals(1, sources.getAllValues().get(0).getOrdinal());
        assertEquals(RESULT_2, sources.getAllValues().get(1).getResultId());
        assertEquals(2, sources.getAllValues().get(1).getOrdinal());
    }

    @Test
    void agentMarkdownBodyIsFrozenAsTextInsteadOfBeingDropped() {
        when(resultMapper.selectList(any())).thenReturn(List.of(
                result(RESULT_2, TASK_2, null, "AGENT", "## 结论\n\n支付重试需幂等。")));
        when(taskMapper.selectList(any())).thenReturn(List.of(
                task(TASK_2, PROJECT_1, "PARTIAL", "FILES", "main")));
        when(targetMapper.selectList(any())).thenReturn(List.of());
        when(projectMapper.selectList(any())).thenReturn(List.of(project()));
        when(issueMapper.selectList(any())).thenReturn(List.of());
        when(feedbackMapper.selectList(any())).thenReturn(List.of());

        ReportDtos.FrozenInput frozen = service.freeze(
                new ReportDtos.FreezeRequest("混合报告", List.of(RESULT_2)));

        JsonNode body = frozen.sources().get(0).snapshot().path("result").path("body");
        assertTrue(body.isTextual());
        assertTrue(body.asText().contains("支付重试需幂等"));
    }

    @Test
    void incompleteOrMissingResultsCannotBeFrozen() {
        when(resultMapper.selectList(any())).thenReturn(List.of(
                result(RESULT_1, TASK_1, TARGET_1, "ISSUE", "{\"summary\":\"部分\"}", "INCOMPLETE")));

        ApiException incomplete = assertThrows(ApiException.class, () -> service.freeze(
                new ReportDtos.FreezeRequest("报告", List.of(RESULT_1))));
        assertEquals(ErrorCode.CONFLICT, incomplete.errorCode());

        ApiException missing = assertThrows(ApiException.class, () -> service.freeze(
                new ReportDtos.FreezeRequest("报告", List.of(RESULT_2))));
        assertEquals(ErrorCode.NOT_FOUND, missing.errorCode());
        verify(reportMapper, never()).insert(any(AgentReport.class));
    }

    @Test
    void duplicateSelectionIsRejectedBeforeWriting() {
        ApiException error = assertThrows(ApiException.class, () -> service.freeze(
                new ReportDtos.FreezeRequest("报告", List.of(RESULT_1, RESULT_1))));

        assertEquals(ErrorCode.VALIDATION_FAILED, error.errorCode());
        verify(resultMapper, never()).selectList(any());
        verify(reportMapper, never()).insert(any(AgentReport.class));
    }

    @Test
    void frozenInputReadsSavedSnapshotsWithoutJoiningLatestData() throws Exception {
        when(reportMapper.selectById(REPORT_ID)).thenReturn(report());
        AgentReportSource source = new AgentReportSource();
        source.setReportId(REPORT_ID);
        source.setResultId(RESULT_1);
        source.setOrdinal(1);
        source.setSourceSnapshot("{\"resultId\":\"" + RESULT_1 + "\",\"kind\":\"ISSUE\",\"result\":{\"body\":{\"summary\":\"冷冻正文\"}}}");
        when(reportSourceMapper.selectList(any())).thenReturn(List.of(source));

        ReportDtos.FrozenInput frozen = service.frozenInput(REPORT_ID);

        assertEquals("订单支付报告", frozen.title());
        assertEquals(1, frozen.sourceCount());
        assertEquals("冷冻正文",
                frozen.sources().get(0).snapshot().path("result").path("body").path("summary").asText());
        assertNotNull(frozen.createdAt());
        verify(resultMapper, never()).selectById(any());
    }

    @Test
    void unknownReportIsNotFound() {
        when(reportMapper.selectById(REPORT_ID)).thenReturn(null);

        ApiException error = assertThrows(ApiException.class, () -> service.frozenInput(REPORT_ID));

        assertEquals(ErrorCode.NOT_FOUND, error.errorCode());
    }

    private AgentReviewResult result(String id, String taskId, String targetId, String kind, String body) {
        return result(id, taskId, targetId, kind, body, "COMPLETE");
    }

    private AgentReviewResult result(String id, String taskId, String targetId, String kind,
                                     String body, String completeness) {
        AgentReviewResult row = new AgentReviewResult();
        row.setId(id);
        row.setTaskId(taskId);
        row.setTargetId(targetId);
        row.setKind(kind);
        row.setSchemaVersion("1");
        row.setCompleteness(completeness);
        row.setBody(body);
        row.setProvenance("{\"materials\":[{\"id\":\"m1\",\"path\":\"A.java\",\"sha\":\"abc123\",\"side\":\"FILE\",\"role\":\"TARGET\"}]}");
        row.setCreatedAt(LocalDateTime.of(2026, 10, 21, 10, 0));
        return row;
    }

    private AgentReviewTask task(String id, String projectId, String state, String sourceKind, String branch) {
        AgentReviewTask row = new AgentReviewTask();
        row.setId(id);
        row.setProjectId(projectId);
        row.setRoute(TASK_2.equals(id) ? "AGENT" : "SPECIALIST");
        row.setSpecialistType("ISSUE");
        row.setState(state);
        row.setInputSnapshot("{\"source\":{\"kind\":\"" + sourceKind + "\",\"branch\":"
                + (branch == null ? "null" : "\"" + branch + "\"") + "},\"targets\":[]}");
        return row;
    }

    private AgentTaskTarget target() {
        AgentTaskTarget row = new AgentTaskTarget();
        row.setId(TARGET_1);
        row.setTaskId(TASK_1);
        row.setPath("A.java");
        row.setHeadSha("abc123");
        row.setBaseSha("base123");
        return row;
    }

    private AgentProject project() {
        AgentProject row = new AgentProject();
        row.setId(PROJECT_1);
        row.setName("订单服务");
        row.setRepoUrl("https://gitea.example.com/team/order-service");
        return row;
    }

    private AgentIssue issue() {
        AgentIssue row = new AgentIssue();
        row.setId(ISSUE_1);
        row.setResultId(RESULT_1);
        row.setOrdinal(1);
        row.setCategory("CORRECTNESS");
        row.setSeverity("HIGH");
        row.setTitle("重复扣款");
        row.setReason("重试没有幂等键");
        row.setSuggestion("增加幂等键");
        row.setLocations("[{\"materialId\":\"m1\",\"startLine\":10,\"endLine\":12}]");
        row.setSourceUnits("[\"u1\"]");
        return row;
    }

    private AgentFeedback feedback(String subjectId, String kind, String assessment, String comment) {
        AgentFeedback row = new AgentFeedback();
        row.setId(AgentIds.next());
        row.setSubjectId(subjectId);
        row.setSubjectKind(kind);
        row.setAssessment(assessment);
        row.setComment(comment);
        row.setRevision(1);
        row.setUpdatedAt(LocalDateTime.of(2026, 10, 21, 11, 0));
        return row;
    }

    private AgentReport report() {
        AgentReport row = new AgentReport();
        row.setId(REPORT_ID);
        row.setTitle("订单支付报告");
        row.setState("INPUT_FROZEN");
        row.setCreatedAt(LocalDateTime.of(2026, 10, 21, 12, 0));
        return row;
    }
}
