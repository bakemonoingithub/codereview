package com.codereview.agent.service;

import com.codereview.agent.api.ApiException;
import com.codereview.agent.api.ErrorCode;
import com.codereview.agent.api.PageResponse;
import com.codereview.agent.db.AgentDatabase;
import com.codereview.agent.dto.AgentReadDtos;
import com.codereview.agent.entity.AgentReviewResult;
import com.codereview.agent.entity.AgentReviewTask;
import com.codereview.agent.entity.AgentRun;
import com.codereview.agent.entity.AgentToolCall;
import com.codereview.agent.entity.AgentModelCall;
import com.codereview.agent.mapper.AgentReviewResultMapper;
import com.codereview.agent.mapper.AgentReviewTaskMapper;
import com.codereview.agent.mapper.AgentRunMapper;
import com.codereview.agent.mapper.AgentToolCallMapper;
import com.codereview.agent.mapper.AgentModelCallMapper;
import com.codereview.agent.support.AgentTime;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentReadServiceTest {

    private static final String TASK_ID = "01J9Z6Q3W4X5Y6Z7A8B9C0D1E2";

    @Mock private AgentDatabase db;
    @Mock private AgentReviewTaskMapper taskMapper;
    @Mock private AgentRunMapper runMapper;
    @Mock private AgentToolCallMapper toolCallMapper;
    @Mock private AgentReviewResultMapper resultMapper;
    @Mock private AgentModelCallMapper modelCallMapper;

    private AgentReadService service;
    private AgentReviewTask task;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new AgentReadService(db, mapper);
        when(db.mapper(AgentReviewTaskMapper.class)).thenReturn(taskMapper);
        when(db.mapper(AgentRunMapper.class)).thenReturn(runMapper);
        when(db.mapper(AgentToolCallMapper.class)).thenReturn(toolCallMapper);
        when(db.mapper(AgentReviewResultMapper.class)).thenReturn(resultMapper);
        when(db.mapper(AgentModelCallMapper.class)).thenReturn(modelCallMapper);
        when(modelCallMapper.selectList(any())).thenReturn(List.of());
        task = new AgentReviewTask();
        task.setId(TASK_ID);
        task.setProjectId("01J9Z6Q3W4X5Y6Z7A8B9C0D1E3");
        task.setRoute("AGENT");
        task.setState("SUCCEEDED");
        task.setConfigSnapshot("{\"limits\":{\"modelTurns\":48,\"toolAttempts\":192}}");
        task.setCreatedAt(LocalDateTime.of(2026, 10, 25, 1, 0));
        task.setStartedAt(task.getCreatedAt().plusSeconds(120));
        task.setFinishedAt(task.getCreatedAt().plusSeconds(310));
        when(taskMapper.selectById(any())).thenReturn(task);
    }

    @Test
    void progressCombinesRunCountersWithFrozenLimits() {
        AgentRun run = new AgentRun();
        run.setTaskId(TASK_ID);
        run.setTurnNo(5);
        run.setToolAttempts(7);
        run.setNetworkAttempts(5);
        run.setCurrentAction("TOOL_CALL");
        run.setCoveredJson("{\"turnNo\":5}");
        run.setState("SUCCEEDED");
        when(runMapper.selectById(TASK_ID)).thenReturn(run);

        AgentReadDtos.Progress progress = service.progress(TASK_ID);

        assertEquals(5, progress.turnNo());
        assertEquals(7, progress.toolAttempts());
        assertEquals(5, progress.networkAttempts());
        assertEquals(48, progress.limits().path("modelTurns").asInt());
        assertEquals(5, progress.covered().path("turnNo").asInt());
        assertEquals(310L, progress.elapsedSeconds());
        assertEquals(0L, progress.outputUsage().totalOutputTokens());
    }

    @Test
    void elapsedTimeIncludesQueueingAndStopsAtThePersistedFinishTime() {
        try (var time = mockStatic(AgentTime.class)) {
            time.when(AgentTime::now).thenReturn(task.getCreatedAt().plusSeconds(600));
            assertEquals(310L, service.progress(TASK_ID).elapsedSeconds());

            task.setState("QUEUED");
            task.setFinishedAt(null);
            assertEquals(600L, service.progress(TASK_ID).elapsedSeconds());

            task.setState("FAILED");
            assertNull(service.progress(TASK_ID).elapsedSeconds());
        }
    }

    @Test
    void outputUsageKeepsCumulativeAndPerCallPeakSeparateAndMarksUnreportedUsage() {
        when(modelCallMapper.selectList(any())).thenReturn(List.of(
                modelCall("SUCCEEDED", "{\"completion_tokens\":12000}"),
                modelCall("SUCCEEDED", "{\"completion_tokens\":9000}"),
                modelCall("SUCCEEDED", "{\"completion_tokens\":0}"),
                modelCall("SUCCEEDED", "{\"completion_tokens\":-1,\"output_tokens\":800}"),
                modelCall("FAILED", "invalid-json"),
                modelCall("SUCCEEDED", "{\"completion_tokens\":\"500\"}"),
                modelCall("RUNNING", null)));

        AgentReadDtos.OutputUsage usage = service.progress(TASK_ID).outputUsage();
        assertEquals(21800L, usage.totalOutputTokens());
        assertEquals(12000L, usage.peakOutputTokens());
        assertEquals(4, usage.reportedCalls());
        assertEquals(2, usage.unreportedCalls());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<QueryWrapper<AgentModelCall>> query = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(modelCallMapper).selectList(query.capture());
        assertEquals("usage_json,state", query.getValue().getSqlSelect());
        assertTrue(query.getValue().getSqlSegment().contains("task_id"));
        assertTrue(query.getValue().getParamNameValuePairs().containsValue(TASK_ID));
    }

    @Test
    void missingOutputUsageIsNotDisplayedAsZero() {
        when(modelCallMapper.selectList(any())).thenReturn(List.of(modelCall("SUCCEEDED", null)));
        AgentReadDtos.OutputUsage usage = service.progress(TASK_ID).outputUsage();
        assertNull(usage.totalOutputTokens());
        assertNull(usage.peakOutputTokens());
        assertEquals(1, usage.unreportedCalls());
    }

    private AgentModelCall modelCall(String state, String usageJson) {
        AgentModelCall call = new AgentModelCall();
        call.setState(state);
        call.setUsageJson(usageJson);
        return call;
    }

    @Test
    void terminalTaskStateWinsOverAStaleRun() {
        AgentReviewTask task = new AgentReviewTask();
        task.setId(TASK_ID);
        task.setRoute("AGENT");
        task.setState("FAILED");
        task.setStopReason("INTERRUPTED");
        task.setConfigSnapshot("{}");
        when(taskMapper.selectById(any())).thenReturn(task);
        AgentRun run = new AgentRun();
        run.setTaskId(TASK_ID);
        run.setTurnNo(4);
        run.setToolAttempts(6);
        run.setNetworkAttempts(4);
        run.setState("RUNNING");
        run.setStopReason(null);
        when(runMapper.selectById(TASK_ID)).thenReturn(run);

        AgentReadDtos.Progress progress = service.progress(TASK_ID);

        assertEquals("FAILED", progress.state());
        assertEquals("INTERRUPTED", progress.stopReason());
        assertEquals(4, progress.turnNo());
        assertEquals(6, progress.toolAttempts());
    }

    @Test
    void toolCallsArePagedAndScopedToTheTask() {
        AgentToolCall row = new AgentToolCall();
        row.setId("01J9Z6Q3W4X5Y6Z7A8B9C0D1E4");
        row.setTaskId(TASK_ID);
        row.setTurnNo(1);
        row.setOrdinal(1);
        row.setToolName("read_code");
        row.setStatus("OK");
        when(toolCallMapper.selectCount(any())).thenReturn(1L);
        when(toolCallMapper.selectList(any())).thenReturn(List.of(row));

        PageResponse<AgentReadDtos.ToolCallSummary> page = service.toolCalls(TASK_ID, 1, 20);

        assertEquals(1, page.items().size());
        assertEquals("read_code", page.items().get(0).toolName());
        assertEquals(1L, page.total());

        AgentToolCall foreign = new AgentToolCall();
        foreign.setId("01J9Z6Q3W4X5Y6Z7A8B9C0D1E5");
        foreign.setTaskId("other");
        when(toolCallMapper.selectById(any())).thenReturn(foreign);
        ApiException error = assertThrows(ApiException.class,
                () -> service.toolCall(TASK_ID, "01J9Z6Q3W4X5Y6Z7A8B9C0D1E5"));
        assertEquals(ErrorCode.NOT_FOUND, error.errorCode());
    }

    @Test
    void resultAndMaterialMetadataAreReadFromTheFrozenResult() {
        AgentReviewResult row = new AgentReviewResult();
        row.setId("result-1");
        row.setTaskId(TASK_ID);
        row.setKind("AGENT");
        row.setSchemaVersion("1");
        row.setCompleteness("COMPLETE");
        row.setBody("{\"title\":\"t\",\"answerStatus\":\"ANSWERED\",\"citations\":[]}");
        row.setProvenance("{\"toolCalls\":7}");
        when(resultMapper.selectList(any())).thenReturn(List.of(row));

        AgentReadDtos.Result result = service.result(TASK_ID);

        assertEquals("1", result.schemaVersion());
        assertEquals("ANSWERED", result.body().path("answerStatus").asText());
        assertEquals(7, result.provenance().path("toolCalls").asInt());
    }

    @Test
    void unfinishedToolIsVisibleWhileRunningAndInterruptedAfterTaskCloses() {
        task.setState("RUNNING");
        AgentToolCall row = new AgentToolCall();
        row.setId("01J9Z6Q3W4X5Y6Z7A8B9C0D1E4");
        row.setTaskId(TASK_ID);
        row.setToolName("search_code");
        row.setStatus("RUNNING");
        row.setArguments("{\"query\":\"needle\"}");
        when(toolCallMapper.selectCount(any())).thenReturn(1L);
        when(toolCallMapper.selectList(any())).thenReturn(List.of(row));
        when(toolCallMapper.selectById(any())).thenReturn(row);

        assertEquals("RUNNING", service.toolCalls(TASK_ID, 1, 20).items().get(0).status());
        var running = service.toolCall(TASK_ID, row.getId());
        assertEquals("RUNNING", running.status());
        assertNull(running.finishedAt());
        assertTrue(running.envelope().isEmpty());

        task.setState("FAILED");
        task.setStopReason("INTERRUPTED");
        assertEquals("INTERRUPTED", service.toolCalls(TASK_ID, 1, 20).items().get(0).status());
        var stopped = service.toolCall(TASK_ID, row.getId());
        assertEquals("INTERRUPTED", stopped.status());
        assertEquals("UNKNOWN", stopped.envelope().path("completeness").asText());
        assertTrue(stopped.envelope().path("diagnostic").asText().contains("未记录最终返回"));
        assertNull(stopped.finishedAt());
    }

    @Test
    void nonAgentTasksAreRejected() {
        AgentReviewTask task = new AgentReviewTask();
        task.setId(TASK_ID);
        task.setRoute("SPECIALIST");
        when(taskMapper.selectById(any())).thenReturn(task);

        ApiException error = assertThrows(ApiException.class, () -> service.progress(TASK_ID));
        assertEquals(ErrorCode.VALIDATION_FAILED, error.errorCode());
    }

    @Test
    void missingResultIsExplicit() {
        when(resultMapper.selectList(any())).thenReturn(List.of());

        ApiException error = assertThrows(ApiException.class, () -> service.result(TASK_ID));
        assertEquals(ErrorCode.NOT_FOUND, error.errorCode());
        assertTrue(error.getMessage().contains("尚未发布"));
    }
}
