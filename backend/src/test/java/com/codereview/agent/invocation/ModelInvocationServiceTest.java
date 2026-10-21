package com.codereview.agent.invocation;

import com.codereview.agent.api.ApiException;
import com.codereview.agent.api.ErrorCode;
import com.codereview.agent.db.AgentDatabase;
import com.codereview.agent.entity.AgentModelCall;
import com.codereview.agent.entity.AgentReviewTask;
import com.codereview.agent.entity.AgentTaskQuota;
import com.codereview.agent.execution.TaskExecutionGuard;
import com.codereview.agent.execution.TaskStates;
import com.codereview.agent.mapper.AgentModelCallMapper;
import com.codereview.agent.mapper.AgentReviewTaskMapper;
import com.codereview.agent.mapper.AgentTaskQuotaMapper;
import com.codereview.agent.prompt.AssembledPrompt;
import com.codereview.agent.prompt.PromptAssemblyService;
import com.codereview.agent.prompt.PromptInput;
import com.codereview.agent.prompt.PromptMaterial;
import com.codereview.agent.prompt.PromptPurpose;
import com.codereview.agent.support.AgentTime;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ModelInvocationServiceTest {

    private static final String TASK_ID = "01J9Z6Q3W4X5Y6Z7A8B9C0D1E2";
    private static final String SHA = "b7a312e0c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6";

    @Mock
    private AgentDatabase db;
    @Mock
    private AgentModelCallMapper callMapper;
    @Mock
    private AgentTaskQuotaMapper quotaMapper;
    @Mock
    private ModelTransport transport;
    @Mock
    private AgentReviewTaskMapper taskMapper;

    private ModelInvocationService service;
    private ModelInvocationProperties properties;
    private AgentReviewTask task;
    private final ObjectMapper mapper = new ObjectMapper();
    private final PromptAssemblyService assembly = new PromptAssemblyService();

    @BeforeEach
    void setUp() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""), AgentModelCall.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""), AgentTaskQuota.class);
        properties = new ModelInvocationProperties();
        properties.setRetryBaseMillis(1);
        service = new ModelInvocationService(db, mapper, transport,
                new ModelCapacityService(properties), assembly,
                new ModelCallConcurrencyLimiter(properties), properties, new TaskExecutionGuard(db));
        task = new AgentReviewTask();
        task.setId(TASK_ID);
        task.setState(TaskStates.RUNNING);
        task.setDeadlineAt(AgentTime.now().plusMinutes(5));
        when(db.mapper(AgentReviewTaskMapper.class)).thenReturn(taskMapper);
        when(taskMapper.selectById(TASK_ID)).thenReturn(task);
        when(db.mapper(AgentModelCallMapper.class)).thenReturn(callMapper);
        when(db.mapper(AgentTaskQuotaMapper.class)).thenReturn(quotaMapper);
        when(callMapper.insert(any(AgentModelCall.class))).thenReturn(1);
        when(callMapper.updateById(any(AgentModelCall.class))).thenReturn(1);
        when(quotaMapper.update(isNull(), any())).thenReturn(1);
    }

    @Test
    void persistsTheExactRequestBeforeSendingAndKeepsTheResponse() {
        when(transport.post(any(), any(), any(), any())).thenReturn(ok("{\"summary\":\"ok\"}"));

        InvocationResult result = service.invoke(request(prompt(100), model(100_000, 8_000)));

        assertEquals("{\"summary\":\"ok\"}", result.content());
        assertEquals(1, result.attempts());
        ArgumentCaptor<AgentModelCall> inserted = ArgumentCaptor.forClass(AgentModelCall.class);
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(callMapper, transport);
        order.verify(callMapper).insert(inserted.capture());
        order.verify(transport).post(any(), any(), any(), any());
        AgentModelCall call = inserted.getValue();
        assertTrue(call.getRequestBody().contains("\"model\":\"deepseek-chat\""));
        assertTrue(call.getRequestBody().contains("\"max_tokens\":4096"));
        assertTrue(call.getRequestBody().contains("\"role\":\"system\""));
        assertFalse(call.getRequestBody().contains("secret"), "凭据不能进入请求正文");
        ArgumentCaptor<AgentModelCall> updated = ArgumentCaptor.forClass(AgentModelCall.class);
        verify(callMapper).updateById(updated.capture());
        assertEquals("SUCCEEDED", updated.getValue().getState());
        assertTrue(updated.getValue().getResponseBody().contains("choices"));
        assertTrue(updated.getValue().getUsageJson().contains("prompt_tokens"));
    }

    @Test
    void transientFailureRetriesOnceAndRecordsEveryAttempt() {
        when(transport.post(any(), any(), any(), any())).thenReturn(
                new ModelTransport.TransportResponse(503, "busy", Map.of(), 5),
                ok("{\"summary\":\"ok\"}"));

        InvocationResult result = service.invoke(request(prompt(100), model(100_000, 8_000)));

        assertEquals(2, result.attempts());
        ArgumentCaptor<AgentModelCall> inserted = ArgumentCaptor.forClass(AgentModelCall.class);
        verify(callMapper, times(2)).insert(inserted.capture());
        assertEquals(1, inserted.getAllValues().get(0).getAttempt());
        assertEquals(2, inserted.getAllValues().get(1).getAttempt());
        verify(transport, times(2)).post(any(), any(), any(), any());
        verify(quotaMapper, times(2)).update(isNull(), any());
    }

    @Test
    void rateLimitRetryHonoursRetryAfterButCapsIt() {
        when(transport.post(any(), any(), any(), any())).thenReturn(
                new ModelTransport.TransportResponse(429, "limited", Map.of("retry-after", "0"), 5),
                ok("{\"summary\":\"ok\"}"));

        InvocationResult result = service.invoke(request(prompt(100), model(100_000, 8_000)));

        assertEquals(2, result.attempts());
        verify(callMapper, times(2)).insert(any(AgentModelCall.class));
    }

    @Test
    void requestPersistenceFailureMeansNoNetworkRequest() {
        when(callMapper.insert(any(AgentModelCall.class))).thenThrow(new RuntimeException("db down"));

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> service.invoke(request(prompt(100), model(100_000, 8_000))));

        assertEquals("db down", error.getMessage());
        verify(transport, never()).post(any(), any(), any(), any());
    }

    @Test
    void authenticationFailureIsNotRetried() {
        when(transport.post(any(), any(), any(), any())).thenReturn(
                new ModelTransport.TransportResponse(401, "bad key", Map.of(), 5));

        ApiException error = assertThrows(ApiException.class,
                () -> service.invoke(request(prompt(100), model(100_000, 8_000))));

        assertEquals(ErrorCode.MODEL_UNAUTHORIZED, error.errorCode());
        verify(transport, times(1)).post(any(), any(), any(), any());
        ArgumentCaptor<AgentModelCall> updated = ArgumentCaptor.forClass(AgentModelCall.class);
        verify(callMapper).updateById(updated.capture());
        assertEquals("FAILED", updated.getValue().getState());
        assertEquals("MODEL_UNAUTHORIZED", updated.getValue().getErrorCode());
    }

    @Test
    void contextOverflowIsRejectedBeforeAnyRequestOrQuotaReservation() {
        ApiException error = assertThrows(ApiException.class,
                () -> service.invoke(request(prompt(64 * 1024), model(5_000, 4_096))));

        assertEquals(ErrorCode.CONTEXT_LIMIT, error.errorCode());
        verify(transport, never()).post(any(), any(), any(), any());
        verify(callMapper, never()).insert(any(AgentModelCall.class));
        verify(quotaMapper, never()).update(any(), any());
    }

    @Test
    void exhaustedQuotaStopsBeforeTheNetworkCall() {
        when(quotaMapper.update(isNull(), any())).thenReturn(0);
        AgentTaskQuota quota = new AgentTaskQuota();
        quota.setTaskId(TASK_ID);
        quota.setHardLimit(200);
        quota.setReserved(200);
        when(quotaMapper.selectById(TASK_ID)).thenReturn(quota);

        ApiException error = assertThrows(ApiException.class,
                () -> service.invoke(request(prompt(100), model(100_000, 8_000))));

        assertEquals(ErrorCode.QUOTA_EXCEEDED, error.errorCode());
        verify(transport, never()).post(any(), any(), any(), any());
        verify(callMapper, never()).insert(any(AgentModelCall.class));
    }

    @Test
    void lengthFinishReasonIsRejectedAsIncompleteOutput() {
        when(transport.post(any(), any(), any(), any())).thenReturn(
                new ModelTransport.TransportResponse(200,
                        response("{\"summary\":\"partial\"}", "length"), Map.of(), 5));

        ApiException error = assertThrows(ApiException.class,
                () -> service.invoke(request(prompt(100), model(100_000, 8_000))));

        assertEquals(ErrorCode.MODEL_OUTPUT_INVALID, error.errorCode());
    }

    @Test
    void validationGoesThroughTheSameAssemblyAndCallRecord() {
        when(transport.post(any(), any(), any(), any())).thenReturn(ok("{\"ok\":true}"));

        ModelInvocationService.ValidationOutcome outcome = service.validate(model(null, null));

        assertTrue(outcome.passed(), outcome.detail());
        ArgumentCaptor<AgentModelCall> inserted = ArgumentCaptor.forClass(AgentModelCall.class);
        verify(callMapper).insert(inserted.capture());
        assertEquals("VALIDATION", inserted.getValue().getPurpose());
        assertEquals(null, inserted.getValue().getTaskId());
        assertTrue(inserted.getValue().getRequestBody().contains("\"max_tokens\":64"));
    }

    @Test
    void validationFailureIsReturnedAsAResultNotAnException() {
        when(transport.post(any(), any(), any(), any())).thenReturn(ok("not-json"));

        ModelInvocationService.ValidationOutcome outcome = service.validate(model(null, null));

        assertFalse(outcome.passed());
    }

    @Test
    void rateLimitWithoutRetryAfterRecordsFailureAndRetries() {
        when(transport.post(any(), any(), any(), any())).thenReturn(
                new ModelTransport.TransportResponse(429, "limited", Map.of(), 5), ok("{}"));

        assertEquals(2, service.invoke(request(prompt(100), model(100_000, 8_000))).attempts());

        ArgumentCaptor<AgentModelCall> saved = ArgumentCaptor.forClass(AgentModelCall.class);
        verify(callMapper, times(2)).updateById(saved.capture());
        assertEquals("FAILED", saved.getAllValues().get(0).getState());
        assertEquals("RATE_LIMITED", saved.getAllValues().get(0).getErrorCode());
        assertEquals("limited", saved.getAllValues().get(0).getResponseBody());
        assertEquals("SUCCEEDED", saved.getAllValues().get(1).getState());
    }

    @Test
    void malformedRetryAfterUsesDefaultBackoff() {
        when(transport.post(any(), any(), any(), any())).thenReturn(
                new ModelTransport.TransportResponse(429, "limited", Map.of("retry-after", "unknown"), 5),
                ok("{}"));

        assertEquals(2, service.invoke(request(prompt(100), model(100_000, 8_000))).attempts());
        verify(quotaMapper, times(2)).update(isNull(), any());
    }

    @Test
    void cancellationDuringTheFirstAttemptStopsRetries() {
        when(transport.post(any(), any(), any(), any())).thenAnswer(invocation -> {
            task.setState(TaskStates.CANCELLED);
            task.setCancelRequestedAt(AgentTime.now());
            return new ModelTransport.TransportResponse(503, "busy", Map.of(), 5);
        });

        ApiException error = assertThrows(ApiException.class,
                () -> service.invoke(request(prompt(100), model(100_000, 8_000))));

        assertEquals(ErrorCode.TASK_CANCELLED, error.errorCode());
        verify(transport).post(any(), any(), any(), any());
        verify(quotaMapper).update(isNull(), any());
        verify(callMapper).insert(any(AgentModelCall.class));
        verify(callMapper).updateById(any(AgentModelCall.class));
    }

    @Test
    void cancellationWhileWaitingReleasesPermitWithoutReservingQuota() {
        ModelCallConcurrencyLimiter actual = new ModelCallConcurrencyLimiter(properties);
        ModelCallConcurrencyLimiter delayed = mock(ModelCallConcurrencyLimiter.class);
        when(delayed.acquire(any(), any())).thenAnswer(invocation -> {
            task.setState(TaskStates.CANCELLED);
            return actual.acquire(TASK_ID, null);
        });
        service = serviceWith(delayed);

        ApiException error = assertThrows(ApiException.class,
                () -> service.invoke(request(prompt(100), model(100_000, 8_000))));

        assertEquals(ErrorCode.TASK_CANCELLED, error.errorCode());
        verify(transport, never()).post(any(), any(), any(), any());
        verify(quotaMapper, never()).update(isNull(), any());
        assertEquals(properties.getGlobalConcurrency(), actual.availableGlobalPermits());
    }

    @Test
    void deadlineExpiredWhileWaitingPreventsSendingAndReleasesPermit() {
        AtomicReference<LocalDateTime> now = new AtomicReference<>(AgentTime.now());
        LocalDateTime deadline = now.get().plusMinutes(1);
        ModelCallConcurrencyLimiter actual = new ModelCallConcurrencyLimiter(properties);
        ModelCallConcurrencyLimiter delayed = mock(ModelCallConcurrencyLimiter.class);
        when(delayed.acquire(any(), any())).thenAnswer(invocation -> {
            now.set(deadline.plusSeconds(1));
            return actual.acquire(TASK_ID, null);
        });
        service = serviceWith(delayed);
        try (MockedStatic<AgentTime> time = mockStatic(AgentTime.class)) {
            time.when(AgentTime::now).thenAnswer(invocation -> now.get());
            ApiException error = assertThrows(ApiException.class, () -> service.invoke(
                    new InvocationRequest(model(100_000, 8_000), prompt(100),
                            new InvocationScope(TASK_ID, null, null, deadline))));
            assertEquals(ErrorCode.TASK_DEADLINE, error.errorCode());
        }
        verify(transport, never()).post(any(), any(), any(), any());
        verify(quotaMapper, never()).update(isNull(), any());
        assertEquals(properties.getGlobalConcurrency(), actual.availableGlobalPermits());
    }

    @Test
    void timeoutIsRecalculatedAfterRequestPersistence() {
        AtomicReference<LocalDateTime> now = new AtomicReference<>(AgentTime.now());
        LocalDateTime deadline = now.get().plusMinutes(1);
        when(callMapper.insert(any(AgentModelCall.class))).thenAnswer(invocation -> {
            now.set(now.get().plusSeconds(30));
            return 1;
        });
        when(transport.post(any(), any(), any(), any())).thenReturn(ok("{}"));
        try (MockedStatic<AgentTime> time = mockStatic(AgentTime.class)) {
            time.when(AgentTime::now).thenAnswer(invocation -> now.get());
            service.invoke(new InvocationRequest(model(100_000, 8_000), prompt(100),
                    new InvocationScope(TASK_ID, null, null, deadline)));
        }
        ArgumentCaptor<Duration> timeout = ArgumentCaptor.forClass(Duration.class);
        verify(transport).post(any(), any(), any(), timeout.capture());
        assertEquals(Duration.ofSeconds(29), timeout.getValue());
    }

    @Test
    void cancellationDuringPersistenceClosesTheUnsentCall() {
        when(callMapper.insert(any(AgentModelCall.class))).thenAnswer(invocation -> {
            task.setCancelRequestedAt(AgentTime.now());
            return 1;
        });
        ApiException error = assertThrows(ApiException.class,
                () -> service.invoke(request(prompt(100), model(100_000, 8_000))));

        assertEquals(ErrorCode.TASK_CANCELLED, error.errorCode());
        verify(transport, never()).post(any(), any(), any(), any());
        ArgumentCaptor<AgentModelCall> saved = ArgumentCaptor.forClass(AgentModelCall.class);
        verify(callMapper).updateById(saved.capture());
        assertEquals("FAILED", saved.getValue().getState());
        assertEquals("TASK_CANCELLED", saved.getValue().getErrorCode());
    }

    private ModelInvocationService serviceWith(ModelCallConcurrencyLimiter limiter) {
        return new ModelInvocationService(db, mapper, transport, new ModelCapacityService(properties),
                assembly, limiter, properties, new TaskExecutionGuard(db));
    }

    @Test
    void expiredDeadlineStopsBeforeSending() {
        InvocationScope expired = new InvocationScope(TASK_ID, null, null,
                com.codereview.agent.support.AgentTime.now().minusMinutes(1));

        ApiException error = assertThrows(ApiException.class, () -> service.invoke(
                new InvocationRequest(model(100_000, 8_000), prompt(100), expired)));

        assertEquals(ErrorCode.TASK_DEADLINE, error.errorCode());
        verify(transport, never()).post(any(), any(), any(), any());
        verify(callMapper, never()).insert(any(AgentModelCall.class));
    }

    private InvocationRequest request(AssembledPrompt prompt, ModelSnapshot model) {
        return new InvocationRequest(model, prompt, new InvocationScope(TASK_ID, null, null,
                com.codereview.agent.support.AgentTime.now().plusMinutes(5)));
    }

    private AssembledPrompt prompt(int contentChars) {
        return assembly.assemble(new PromptInput(PromptPurpose.ISSUE_FILE, "1", null, null, null,
                "FILES", "main", SHA, null,
                List.of(new PromptMaterial("target-1", "src/Big.java", SHA, "FILE", 1, 1,
                        "x".repeat(contentChars), "COMPLETE", null)),
                List.of(), List.of(), null));
    }

    private static ModelSnapshot model(Integer window, Integer maxOutput) {
        return new ModelSnapshot("01J9Z6Q3W4X5Y6Z7A8B9C0D1E3", "默认", "https://gateway.local/v1",
                "deepseek-chat", "secret", window, maxOutput, null, "OPENAI_CHAT", 0);
    }

    private static ModelTransport.TransportResponse ok(String content) {
        return new ModelTransport.TransportResponse(200, response(content, "stop"), Map.of(), 5);
    }

    private static String response(String content, String finishReason) {
        return "{\"choices\":[{\"message\":{\"content\":" + quote(content) + "},"
                + "\"finish_reason\":\"" + finishReason + "\"}],"
                + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}";
    }

    private static String quote(String value) {
        try {
            return new ObjectMapper().writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
