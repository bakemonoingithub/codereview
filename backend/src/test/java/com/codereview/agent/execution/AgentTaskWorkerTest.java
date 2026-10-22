package com.codereview.agent.execution;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.codereview.agent.api.ApiException;
import com.codereview.agent.api.ErrorCode;
import com.codereview.agent.db.AgentDatabase;
import com.codereview.agent.entity.AgentModelConfig;
import com.codereview.agent.entity.AgentReviewTask;
import com.codereview.agent.entity.AgentRun;
import com.codereview.agent.entity.AgentToolCall;
import com.codereview.agent.invocation.InvocationResult;
import com.codereview.agent.invocation.ModelInvocationService;
import com.codereview.agent.mapper.AgentCallMaterialMapper;
import com.codereview.agent.mapper.AgentModelConfigMapper;
import com.codereview.agent.mapper.AgentRunMapper;
import com.codereview.agent.mapper.AgentToolCallMapper;
import com.codereview.agent.prompt.PromptAssemblyService;
import com.codereview.agent.prompt.ToolCall;
import com.codereview.agent.support.AgentTime;
import com.codereview.agent.tools.AgentSubmission;
import com.codereview.agent.tools.ReadOnlyToolService;
import com.codereview.agent.tools.ToolEnvelope;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.mockito.Mockito.atLeastOnce;
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
        when(callMaterialMapper.insert(any(com.codereview.agent.entity.AgentCallMaterial.class))).thenReturn(1);
        when(tools.execute(anyString(), anyString(), anyString(), any())).thenReturn(envelope());
        when(publisher.publish(anyString(), any(), any(), any(), anyInt())).thenReturn("result-1");
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
    void repeatedIdenticalCallsStopAsNoProgress() {
        when(invocation.invoke(any())).thenReturn(toolCallResult("call-x", "read_code",
                "{\"version\":\"V1\",\"path\":\"src/A.java\"}"));

        TaskWorker.TaskOutcome outcome = worker.execute(task);

        assertEquals(TaskStates.PARTIAL, outcome.state());
        assertEquals(TaskStates.STOP_NO_PROGRESS_LIMIT, outcome.stopReason());
        verify(invocation, org.mockito.Mockito.times(3)).invoke(any());
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
    void contentOnlyRepliesExhaustTheProtocolRetries() {
        when(invocation.invoke(any())).thenReturn(textResult("call-1", "我先说明一下计划。"));

        TaskWorker.TaskOutcome outcome = worker.execute(task);

        assertEquals(TaskStates.FAILED, outcome.state());
        assertEquals(TaskStates.STOP_PROTOCOL_INVALID, outcome.stopReason());
        verify(invocation, org.mockito.Mockito.times(3)).invoke(any());
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
