package com.codereview.agent.execution;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.codereview.agent.api.ApiException;
import com.codereview.agent.api.ErrorCode;
import com.codereview.agent.db.AgentDatabase;
import com.codereview.agent.entity.AgentModelConfig;
import com.codereview.agent.entity.AgentReviewTask;
import com.codereview.agent.entity.AgentRun;
import com.codereview.agent.entity.AgentToolCall;
import com.codereview.agent.invocation.InvocationRequest;
import com.codereview.agent.invocation.InvocationResult;
import com.codereview.agent.invocation.ModelInvocationService;
import com.codereview.agent.mapper.AgentCallMaterialMapper;
import com.codereview.agent.mapper.AgentModelConfigMapper;
import com.codereview.agent.mapper.AgentReviewTaskMapper;
import com.codereview.agent.mapper.AgentRunMapper;
import com.codereview.agent.mapper.AgentToolCallMapper;
import com.codereview.agent.prompt.PromptMessage;
import com.codereview.agent.prompt.PromptAssemblyService;
import com.codereview.agent.prompt.ToolCall;
import com.codereview.agent.support.AgentTime;
import com.codereview.agent.tools.AgentSubmission;
import com.codereview.agent.tools.ReadOnlyToolService;
import com.codereview.agent.tools.ToolEnvelope;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentTaskWorkerTest {

    private static final String TASK_ID = "01J9Z6Q3W4X5Y6Z7A8B9C0D1E2";
    private static final String PROJECT_ID = "01J9Z6Q3W4X5Y6Z7A8B9C0D1E3";
    private static final String MODEL_ID = "01M23AKQZRG40082QFECKMC36N";
    private static final String SHA = "b7a312e0c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6";

    @Mock private AgentDatabase db;
    @Mock private TaskExecutionGuard guard;
    @Mock private ModelInvocationService invocation;
    @Mock private ReadOnlyToolService tools;
    @Mock private AgentResultPublisher publisher;
    @Mock private AgentRunMapper runMapper;
    @Mock private AgentReviewTaskMapper taskMapper;
    @Mock private AgentModelConfigMapper modelMapper;
    @Mock private AgentToolCallMapper toolCallMapper;
    @Mock private AgentCallMaterialMapper callMaterialMapper;

    private AgentTaskWorker worker;
    private final ObjectMapper mapper = new ObjectMapper();
    private AgentReviewTask task;
    private AgentModelConfig model;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new org.apache.ibatis.builder.MapperBuilderAssistant(
                new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""), AgentRun.class);
        worker = new AgentTaskWorker(db, mapper, guard, new PromptAssemblyService(), invocation,
                tools, publisher);
        model = new AgentModelConfig();
        model.setId(MODEL_ID);
        model.setName("DeepSeek");
        model.setBaseUrl("https://api.deepseek.com");
        model.setModelName("deepseek-flash");
        model.setProtocol("OPENAI_CHAT");
        model.setContextWindow(100_000);
        model.setMaxOutput(20_000);
        model.setRevision(2);
        model.setCredential("secret");

        task = new AgentReviewTask();
        task.setId(TASK_ID);
        task.setProjectId(PROJECT_ID);
        task.setState(TaskStates.RUNNING);
        task.setDeadlineAt(AgentTime.now().plusMinutes(10));
        task.setInputSnapshot(inputSnapshot());
        task.setConfigSnapshot(configSnapshot());

        when(db.mapper(AgentRunMapper.class)).thenReturn(runMapper);
        when(db.mapper(AgentReviewTaskMapper.class)).thenReturn(taskMapper);
        when(taskMapper.selectById(TASK_ID)).thenReturn(task);
        when(db.mapper(AgentModelConfigMapper.class)).thenReturn(modelMapper);
        when(db.mapper(AgentToolCallMapper.class)).thenReturn(toolCallMapper);
        when(db.mapper(AgentCallMaterialMapper.class)).thenReturn(callMaterialMapper);
        when(guard.requireActive(TASK_ID)).thenReturn(task);
        when(modelMapper.selectById(MODEL_ID)).thenReturn(model);
        when(runMapper.selectById(TASK_ID)).thenReturn(null);
        when(runMapper.insert(any(AgentRun.class))).thenReturn(1);
        when(runMapper.updateById(any(AgentRun.class))).thenReturn(1);
        when(runMapper.update(any(), any())).thenReturn(1);
        when(toolCallMapper.insert(any(AgentToolCall.class))).thenReturn(1);
        when(toolCallMapper.updateById(any(AgentToolCall.class))).thenReturn(1);
        when(callMaterialMapper.insert(any(com.codereview.agent.entity.AgentCallMaterial.class))).thenReturn(1);
        when(tools.execute(anyString(), anyString(), anyString(), any())).thenReturn(envelope());
        when(publisher.publish(anyString(), any(), any(), any(), anyInt())).thenReturn("result-1");
    }

    @Test
    void frozenCustomBudgetControlsLoopAndInvocation() throws Exception {
        var config = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(task.getConfigSnapshot());
        config.put("version", 3);
        AgentBudgetProperties budget = new AgentBudgetProperties(); budget.setModelTurns(1); budget.setOutputTokens(2048);
        config.set("limits", mapper.valueToTree(budget));
        var toolsConfig = new com.codereview.agent.tools.ToolLimitProperties(); toolsConfig.setReadMaxLines(250);
        config.set("toolLimits", mapper.valueToTree(toolsConfig));
        var policy = new com.codereview.agent.invocation.InvocationPolicy(0, 5, 30, 512, 0.05);
        config.set("invocationPolicy", mapper.valueToTree(policy));
        task.setConfigSnapshot(mapper.writeValueAsString(config));
        when(invocation.invoke(any())).thenReturn(toolCallResult("call-1", "read_code",
                "{\"version\":\"V1\",\"path\":\"src/A.java\"}"));
        var outcome = worker.execute(task);
        assertEquals(TaskStates.STOP_TURN_LIMIT, outcome.stopReason());
        ArgumentCaptor<InvocationRequest> request = ArgumentCaptor.forClass(InvocationRequest.class);
        verify(invocation).invoke(request.capture());
        assertEquals(2048, request.getValue().outputReserve());
        assertEquals(policy, request.getValue().policy());
        var scope = ArgumentCaptor.forClass(com.codereview.agent.tools.AgentToolScope.class);
        verify(tools).execute(anyString(), anyString(), anyString(), scope.capture());
        assertEquals(250, scope.getValue().limits().getReadMaxLines());
    }

    @Test
    void singleRemainingNetworkAttemptDisablesRetryWithoutSkippingTheCall() throws Exception {
        var config = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(task.getConfigSnapshot());
        var limits = config.withObject("limits");
        limits.put("networkAttempts", 1);
        task.setConfigSnapshot(mapper.writeValueAsString(config));
        when(invocation.invoke(any())).thenReturn(toolCallResult("call-1", "read_code",
                "{\"version\":\"V1\",\"path\":\"src/A.java\"}"));
        var outcome = worker.execute(task);
        assertEquals(TaskStates.STOP_NETWORK_LIMIT, outcome.stopReason());
        ArgumentCaptor<InvocationRequest> request = ArgumentCaptor.forClass(InvocationRequest.class);
        verify(invocation).invoke(request.capture());
        assertEquals(0, request.getValue().policy().transportRetries());
    }

    @Test
    void validSubmitPublishesTheResult() {
        when(invocation.invoke(any()))
                .thenReturn(toolCallResult("call-1", "read_code",
                        "{\"version\":\"V1\",\"path\":\"src/A.java\"}"))
                .thenReturn(toolCallResult("call-2", "submit_result", submitJson()));

        TaskWorker.TaskOutcome outcome = worker.execute(task);

        assertEquals(TaskStates.SUCCEEDED, outcome.state());
        ArgumentCaptor<AgentSubmission> submission = ArgumentCaptor.forClass(AgentSubmission.class);
        verify(publisher).publish(eq(TASK_ID), submission.capture(), any(), any(), anyInt());
        assertEquals(AgentSubmission.ANSWERED, submission.getValue().answerStatus());
        assertEquals(1, submission.getValue().citations().size());
        verify(tools).execute(eq("p1"), eq("read_code"), anyString(), any());
        verify(runMapper, atLeastOnce()).update(any(), any());
    }

    @Test
    void frozenFilePathsAndGuidanceReachFirstAndFollowUpModelRequests() throws Exception {
        var snapshot = mapper.readTree(task.getInputSnapshot());
        ((com.fasterxml.jackson.databind.node.ObjectNode) snapshot.path("scope"))
                .putArray("paths").add("src/A.java").add("src/test/BTest.java");
        task.setInputSnapshot(mapper.writeValueAsString(snapshot));
        var config = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(task.getConfigSnapshot());
        config.putObject("prompt").put("versionNo", 1).put("content", "冻结的历史指导正文");
        task.setConfigSnapshot(mapper.writeValueAsString(config));
        when(invocation.invoke(any()))
                .thenReturn(toolCallResult("call-1", "read_code",
                        "{\"version\":\"V1\",\"path\":\"src/A.java\"}"))
                .thenReturn(toolCallResult("call-2", "submit_result", submitJson()));

        assertEquals(TaskStates.SUCCEEDED, worker.execute(task).state());

        ArgumentCaptor<InvocationRequest> requests = ArgumentCaptor.forClass(InvocationRequest.class);
        verify(invocation, org.mockito.Mockito.times(2)).invoke(requests.capture());
        for (InvocationRequest request : requests.getAllValues()) {
            String user = request.prompt().messages().get(1).content();
            assertTrue(user.contains("- src/A.java\n"));
            assertTrue(user.contains("- src/test/BTest.java\n"));
            assertTrue(user.contains("V1 = " + SHA));
            assertTrue(user.contains("冻结的历史指导正文"));
        }
    }

    @Test
    void repeatedIdenticalCallsStopAsNoProgress() {
        when(invocation.invoke(any())).thenReturn(toolCallResult("call-x", "read_code",
                "{\"version\":\"V1\",\"path\":\"src/A.java\"}"));

        TaskWorker.TaskOutcome outcome = worker.execute(task);

        assertEquals(TaskStates.PARTIAL, outcome.state());
        assertEquals(TaskStates.STOP_NO_PROGRESS_LIMIT, outcome.stopReason());
        verify(invocation, org.mockito.Mockito.times(3)).invoke(any());
    }

    @Test
    void runningToolIsPersistedBeforeExecutionAndUpdatedWithTheFailure() throws Exception {
        var config = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(task.getConfigSnapshot());
        config.withObject("limits").put("modelTurns", 1);
        task.setConfigSnapshot(mapper.writeValueAsString(config));
        when(invocation.invoke(any()))
                .thenAnswer(i -> {
                    ArgumentCaptor<AgentRun> runs = ArgumentCaptor.forClass(AgentRun.class);
                    verify(runMapper, atLeastOnce()).updateById(runs.capture());
                    assertEquals("MODEL_CALL", runs.getValue().getCurrentAction());
                    return toolCallResult("call-1", "search_code", "{\"version\":\"V1\",\"query\":\"needle\"}");
                });
        when(tools.execute(anyString(), anyString(), anyString(), any())).thenAnswer(i -> {
            ArgumentCaptor<AgentToolCall> records = ArgumentCaptor.forClass(AgentToolCall.class);
            verify(toolCallMapper).insert(records.capture());
            AgentToolCall running = records.getValue();
            assertEquals("RUNNING", running.getStatus());
            assertTrue(running.getStartedAt() != null);
            assertEquals(null, running.getFinishedAt());
            assertEquals(null, running.getEnvelope());
            ArgumentCaptor<AgentRun> runs = ArgumentCaptor.forClass(AgentRun.class);
            verify(runMapper, atLeastOnce()).updateById(runs.capture());
            assertEquals("TOOL_CALL:search_code", runs.getValue().getCurrentAction());
            return new ToolEnvelope("p1", ToolEnvelope.UNREADABLE, List.of(), null,
                    List.of(), null, ToolEnvelope.UNKNOWN, null, "镜像准备失败：connection failed");
        });

        worker.execute(task);

        ArgumentCaptor<AgentToolCall> completed = ArgumentCaptor.forClass(AgentToolCall.class);
        verify(toolCallMapper).updateById(completed.capture());
        assertEquals("UNREADABLE", completed.getValue().getStatus());
        assertTrue(completed.getValue().getFinishedAt() != null);
        assertTrue(completed.getValue().getEnvelope().contains("connection failed"));
        ArgumentCaptor<AgentToolCall> inserted = ArgumentCaptor.forClass(AgentToolCall.class);
        verify(toolCallMapper).insert(inserted.capture());
        assertEquals(inserted.getAllValues().get(0).getId(), completed.getValue().getId());
    }

    @Test
    void unexpectedToolFailureClosesItsRunningRecordAndFailsTheTask() {
        when(invocation.invoke(any())).thenReturn(toolCallResult("call-1", "read_code", "{}"));
        when(tools.execute(anyString(), anyString(), anyString(), any()))
                .thenThrow(new IllegalStateException("unexpected storage failure"));

        assertEquals(TaskStates.FAILED, worker.execute(task).state());

        ArgumentCaptor<AgentToolCall> completed = ArgumentCaptor.forClass(AgentToolCall.class);
        verify(toolCallMapper).updateById(completed.capture());
        assertEquals("ERROR", completed.getValue().getStatus());
        assertTrue(completed.getValue().getFinishedAt() != null);
        verify(publisher, never()).publish(anyString(), any(), any(), any(), anyInt());
    }

    @Test
    void deadlineAlreadyClosedIsNotRecordedAsUserCancel() {
        task.setState(TaskStates.FAILED);
        task.setStopReason(TaskStates.STOP_TASK_DEADLINE);
        when(guard.requireActive(TASK_ID)).thenThrow(
                new ApiException(ErrorCode.TASK_CANCELLED, "任务已结束，停止新的调用"));

        TaskWorker.TaskOutcome outcome = worker.execute(task);

        assertEquals(TaskStates.FAILED, outcome.state());
        assertEquals(TaskStates.STOP_TASK_DEADLINE, outcome.stopReason());
        verify(publisher, never()).publish(anyString(), any(), any(), any(), anyInt());
    }

    @Test
    void cancellationStopsWithoutPublishing() {
        when(invocation.invoke(any())).thenThrow(
                new ApiException(ErrorCode.TASK_CANCELLED, "任务已取消"));

        TaskWorker.TaskOutcome outcome = worker.execute(task);

        assertEquals(TaskStates.CANCELLED, outcome.state());
        assertEquals(TaskStates.STOP_USER_CANCELLED, outcome.stopReason());
        verify(publisher, org.mockito.Mockito.never()).publish(anyString(), any(), any(), any(), anyInt());
    }

    @Test
    @org.junit.jupiter.api.DisplayName("AGT-017 cancellation while model response is held after a successful read")
    void agt017LateModelResponseCannotExecuteTools() throws Exception {
        task.setName("AGT-017 受控模型等待取消");
        var reached = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean(false);
        when(guard.requireActive(TASK_ID)).thenAnswer(i -> {
            if (cancelled.get()) throw new ApiException(ErrorCode.TASK_CANCELLED, "AGT-017 cancelled");
            return task;
        });
        when(invocation.invoke(any()))
                .thenReturn(toolCallResult("call-1", "read_code", "{\"version\":\"V1\",\"path\":\"src/A.java\"}"))
                .thenAnswer(i -> {
                    reached.countDown();
                    assertTrue(release.await(20, java.util.concurrent.TimeUnit.SECONDS));
                    return toolCallResult("late-call", "read_code", "{\"version\":\"V1\",\"path\":\"src/B.java\"}");
                });
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var future = executor.submit(() -> worker.execute(task));
            assertTrue(reached.await(20, java.util.concurrent.TimeUnit.SECONDS));
            cancelled.set(true);
            release.countDown();
            assertEquals(TaskStates.CANCELLED, future.get(20, java.util.concurrent.TimeUnit.SECONDS).state());
            verify(invocation, org.mockito.Mockito.times(2)).invoke(any());
            verify(tools, org.mockito.Mockito.times(1)).execute(anyString(), anyString(), anyString(), any());
            verify(publisher, never()).publish(anyString(), any(), any(), any(), anyInt());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void contentOnlyRepliesExhaustTheProtocolRetries() {
        when(invocation.invoke(any())).thenReturn(textResult("call-1", "我先说明一下计划。"));

        TaskWorker.TaskOutcome outcome = worker.execute(task);

        assertEquals(TaskStates.FAILED, outcome.state());
        assertEquals(TaskStates.STOP_PROTOCOL_INVALID, outcome.stopReason());
        verify(invocation, org.mockito.Mockito.times(3)).invoke(any());
    }

    @Test
    void rejectedOversizedToolBatchGetsOneToolResultPerCallBeforeRetry() {
        List<ToolCall> oversized = java.util.stream.IntStream.range(0, 8)
                .mapToObj(index -> new ToolCall("bad-" + index, "read_code", "{}"))
                .toList();
        when(invocation.invoke(any()))
                .thenReturn(toolCallResult("call-invalid", oversized))
                .thenReturn(toolCallResult("call-read", "read_code",
                        "{\"version\":\"V1\",\"path\":\"src/A.java\"}"))
                .thenReturn(toolCallResult("call-submit", "submit_result", submitJson()));

        TaskWorker.TaskOutcome outcome = worker.execute(task);

        assertEquals(TaskStates.SUCCEEDED, outcome.state());
        ArgumentCaptor<InvocationRequest> requests = ArgumentCaptor.forClass(InvocationRequest.class);
        verify(invocation, org.mockito.Mockito.times(3)).invoke(requests.capture());
        List<PromptMessage> retryMessages = requests.getAllValues().get(1).prompt().messages();
        List<PromptMessage> rejectedAssistant = retryMessages.stream()
                .filter(message -> "assistant".equals(message.role()) && !message.toolCalls().isEmpty())
                .toList();
        List<PromptMessage> rejectedResults = retryMessages.stream()
                .filter(message -> "tool".equals(message.role()))
                .toList();
        assertEquals(1, rejectedAssistant.size());
        assertEquals(8, rejectedAssistant.get(0).toolCalls().size());
        assertEquals(8, rejectedResults.size());
        assertEquals(rejectedAssistant.get(0).toolCalls().stream().map(ToolCall::id).toList(),
                rejectedResults.stream().map(PromptMessage::toolCallId).toList());
        verify(tools, org.mockito.Mockito.times(1)).execute(anyString(), eq("read_code"), anyString(), any());
    }

    @Test
    void mixedSubmitBatchIsRejectedRecordedAndNotExecuted() {
        List<ToolCall> mixed = List.of(
                new ToolCall("bad-submit", "submit_result", submitJson()),
                new ToolCall("bad-read", "read_code", "{}"));
        when(invocation.invoke(any()))
                .thenReturn(toolCallResult("call-invalid", mixed))
                .thenReturn(toolCallResult("call-read", "read_code",
                        "{\"version\":\"V1\",\"path\":\"src/A.java\"}"))
                .thenReturn(toolCallResult("call-submit", "submit_result", submitJson()));

        TaskWorker.TaskOutcome outcome = worker.execute(task);

        assertEquals(TaskStates.SUCCEEDED, outcome.state());
        ArgumentCaptor<AgentToolCall> records = ArgumentCaptor.forClass(AgentToolCall.class);
        verify(toolCallMapper, org.mockito.Mockito.times(4)).insert(records.capture());
        List<AgentToolCall> rejected = records.getAllValues().stream()
                .filter(record -> "REJECTED_BATCH".equals(record.getStatus())).toList();
        assertEquals(2, rejected.size());
        assertTrue(rejected.stream().allMatch(record -> record.getEnvelope().contains("本批工具没有执行")));
        verify(tools, org.mockito.Mockito.times(1)).execute(anyString(), eq("read_code"), anyString(), any());
    }

    @Test
    void finishingPersistsTheTurnThatEndedTheRun() {
        when(invocation.invoke(any()))
                .thenReturn(toolCallResult("call-1", "read_code",
                        "{\"version\":\"V1\",\"path\":\"src/A.java\"}"))
                .thenReturn(toolCallResult("call-2", "submit_result", submitJson()));

        worker.execute(task);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<AgentRun>> updates =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(runMapper, atLeastOnce()).update(isNull(), updates.capture());
        LambdaUpdateWrapper<AgentRun> last = updates.getAllValues().get(updates.getAllValues().size() - 1);
        String sql = last.getSqlSet();
        assertTrue(sql.contains("turn_no"), sql);
        assertTrue(sql.contains("tool_attempts"), sql);
        assertTrue(sql.contains("network_attempts"), sql);
        assertTrue(sql.contains("covered_json"), sql);
        assertTrue(last.getParamNameValuePairs().containsValue(2), last.getParamNameValuePairs().toString());
        assertTrue(last.getParamNameValuePairs().containsValue(1), last.getParamNameValuePairs().toString());
    }

    @Test
    void sentLinkFailureDoesNotLetTheModelCiteUnrecordedMaterials() {
        when(callMaterialMapper.insert(any(com.codereview.agent.entity.AgentCallMaterial.class)))
                .thenThrow(new RuntimeException("关联写入失败"));
        when(invocation.invoke(any()))
                .thenReturn(toolCallResult("call-1", "read_code",
                        "{\"version\":\"V1\",\"path\":\"src/A.java\"}"))
                .thenReturn(toolCallResult("call-2", "submit_result", submitJson()));

        TaskWorker.TaskOutcome outcome = worker.execute(task);

        assertEquals(TaskStates.FAILED, outcome.state());
        assertEquals(TaskStates.STOP_EXECUTION_ERROR, outcome.stopReason());
        verify(publisher, never()).publish(anyString(), any(), any(), any(), anyInt());
    }

    @Test
    void duplicateSentLinkStillAllowsTheAlreadyRecordedMaterial() {
        when(callMaterialMapper.insert(any(com.codereview.agent.entity.AgentCallMaterial.class)))
                .thenThrow(new DuplicateKeyException("已存在"));
        when(invocation.invoke(any()))
                .thenReturn(toolCallResult("call-1", "read_code",
                        "{\"version\":\"V1\",\"path\":\"src/A.java\"}"))
                .thenReturn(toolCallResult("call-2", "submit_result", submitJson()));

        TaskWorker.TaskOutcome outcome = worker.execute(task);

        assertEquals(TaskStates.SUCCEEDED, outcome.state());
        verify(publisher).publish(eq(TASK_ID), any(), any(), any(), anyInt());
    }

    @Test
    void smallContextWindowStopsBeforeCallingTheModel() {
        model.setContextWindow(1_000);

        TaskWorker.TaskOutcome outcome = worker.execute(task);

        assertEquals(TaskStates.FAILED, outcome.state());
        assertEquals(TaskStates.STOP_CONTEXT_LIMIT, outcome.stopReason());
        verify(invocation, org.mockito.Mockito.never()).invoke(any());
    }

    @Test
    void invalidSubmitGetsALimitedCorrectionTurn() {
        when(invocation.invoke(any()))
                .thenReturn(toolCallResult("call-1", "read_code",
                        "{\"version\":\"V1\",\"path\":\"src/A.java\"}"))
                .thenReturn(toolCallResult("call-2", "submit_result", """
                        {"title":"t","summary":"s","answerStatus":"ANSWERED","markdown":"没有引用"}
                        """))
                .thenReturn(toolCallResult("call-3", "submit_result", submitJson()));

        TaskWorker.TaskOutcome outcome = worker.execute(task);

        assertEquals(TaskStates.SUCCEEDED, outcome.state());
        verify(publisher).publish(eq(TASK_ID), any(), any(), any(), anyInt());
    }

    private InvocationResult toolCallResult(String callId, String name, String arguments) {
        return new InvocationResult(callId, null, null, "tool_calls", 1, 100, 1024, "m", "v",
                List.of(new ToolCall("p1", name, arguments)));
    }

    private InvocationResult toolCallResult(String callId, List<ToolCall> calls) {
        return new InvocationResult(callId, null, null, "tool_calls", 1, 100, 1024, "m", "v", calls);
    }

    private InvocationResult textResult(String callId, String content) {
        return new InvocationResult(callId, content, null, "stop", 1, 100, 1024, "m", "v");
    }

    private static String submitJson() {
        return """
                {"title":"重试链路","summary":"调用方尚缺完整幂等保证。","answerStatus":"ANSWERED",
                 "markdown":"结论：重试入口再次调用支付接口。[E1]",
                 "citations":[{"id":"E1","materialId":"m1","startLine":1,"endLine":2}],
                 "unresolved":[]}
                """;
    }

    private static ToolEnvelope envelope() {
        return new ToolEnvelope("p1", ToolEnvelope.OK,
                List.of(new ToolEnvelope.VersionRef("V1", SHA)), null,
                List.of(new ToolEnvelope.ToolMaterial("m1", "src/A.java", "FILE", SHA, 1, 5,
                        "hash", ToolEnvelope.COMPLETE, "class A {}")),
                Map.of("path", "src/A.java"), ToolEnvelope.COMPLETE, null, null);
    }

    private String inputSnapshot() {
        return toJson(Map.of(
                "version", 2,
                "requirements", "检查重试链路",
                "scope", Map.of("kind", "FILES", "observedSha", SHA, "headSha", SHA,
                        "versions", List.of(Map.of("version", "V1", "sha", SHA)),
                        "comparisons", List.of())));
    }

    private String configSnapshot() {
        return toJson(Map.of(
                "version", 2,
                "model", Map.of("id", MODEL_ID, "revision", 2),
                "limits", Map.of("modelTurns", 48),
                "instructions", Map.of("builtinVersion", "1")));
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
