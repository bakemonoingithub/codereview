package com.codereview.agent.tools;

import com.codereview.agent.api.ApiException;
import com.codereview.agent.api.ErrorCode;
import com.codereview.agent.invocation.InvocationResult;
import com.codereview.agent.invocation.ModelInvocationService;
import com.codereview.agent.invocation.ModelSnapshot;
import com.codereview.agent.prompt.PromptAssemblyService;
import com.codereview.agent.prompt.ToolCall;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentToolLoopValidatorTest {

    @Mock
    private ModelInvocationService invocation;

    private AgentToolLoopValidator validator;

    @BeforeEach
    void setUp() {
        validator = new AgentToolLoopValidator(invocation, new PromptAssemblyService(),
                new ObjectMapper());
    }

    @Test
    void validationUsesConfiguredPerRoundDeadlineAndOutput() {
        var p = new com.codereview.agent.invocation.ModelInvocationProperties();
        p.setToolValidationTimeoutSeconds(600); p.setToolValidationOutputTokens(2048);
        validator = new AgentToolLoopValidator(invocation, new PromptAssemblyService(), new ObjectMapper(),
                new ToolLimitProperties(), new com.codereview.agent.execution.AgentBudgetProperties(), p);
        when(invocation.invoke(any())).thenReturn(textResult("call-1", "no tool"));
        var started = com.codereview.agent.support.AgentTime.now();
        validator.validate(model());
        var request = org.mockito.ArgumentCaptor.forClass(com.codereview.agent.invocation.InvocationRequest.class);
        verify(invocation).invoke(request.capture());
        assertEquals(2048, request.getValue().outputReserve());
        assertTrue(request.getValue().scope().deadlineAt().isAfter(started.plusSeconds(590)));
        assertTrue(request.getValue().scope().deadlineAt().isBefore(started.plusSeconds(610)));
    }

    @Test
    void twoRoundsOfRealCallsPass() {
        when(invocation.invoke(any()))
                .thenReturn(toolCallResult("call-1", "read_code",
                        "{\"version\":\"V1\",\"path\":\"fixture/Fixture.java\",\"start\":1,\"end\":3}"))
                .thenReturn(textResult("call-2", "收到了第 1 到 3 行。"));

        AgentToolLoopValidator.Outcome outcome = validator.validate(model());

        assertTrue(outcome.passed(), outcome.detail());
        assertEquals(List.of("call-1", "call-2"), outcome.callIds());
        assertEquals(AgentToolSchema.version(new ToolLimitProperties()), outcome.toolSchemaVersion());
        assertEquals(64, outcome.optionsHash().length());
        assertTrue(outcome.detail().contains("read_code"));
        verify(invocation, org.mockito.Mockito.times(2)).invoke(any());
    }

    @Test
    void noToolCallInTheFirstRoundFailsTheCheck() {
        when(invocation.invoke(any())).thenReturn(textResult("call-1", "我直接回答了。"));

        AgentToolLoopValidator.Outcome outcome = validator.validate(model());

        assertFalse(outcome.passed());
        assertTrue(outcome.detail().contains("没有返回工具调用"));
        assertEquals(1, outcome.callIds().size());
    }

    @Test
    void invalidArgumentsFailWithoutASecondRound() {
        when(invocation.invoke(any())).thenReturn(toolCallResult("call-1", "read_code",
                "{\"version\":\"V9\",\"path\":\"fixture/Fixture.java\"}"));

        AgentToolLoopValidator.Outcome outcome = validator.validate(model());

        assertFalse(outcome.passed());
        assertTrue(outcome.detail().contains("工具参数未通过校验"), outcome.detail());
        verify(invocation, org.mockito.Mockito.times(1)).invoke(any());
    }

    @Test
    void unknownToolNameFailsTheCheck() {
        when(invocation.invoke(any())).thenReturn(toolCallResult("call-1", "write_file", "{}"));

        AgentToolLoopValidator.Outcome outcome = validator.validate(model());

        assertFalse(outcome.passed());
        assertTrue(outcome.detail().contains("只读集合"));
    }

    @Test
    void apiFailureIsReportedAsAFailedCheck() {
        when(invocation.invoke(any())).thenThrow(
                new ApiException(ErrorCode.MODEL_BAD_REQUEST, "模型请求被拒绝"));

        AgentToolLoopValidator.Outcome outcome = validator.validate(model());

        assertFalse(outcome.passed());
        assertTrue(outcome.detail().contains("模型请求被拒绝"));
        assertTrue(outcome.callIds().isEmpty());
    }

    @Test
    void optionsHashChangesWithTheConfiguredRevision() {
        ModelSnapshot base = model();
        ModelSnapshot revised = new ModelSnapshot(base.id(), base.name(), base.baseUrl(),
                base.modelName(), base.credential(), base.contextWindow(), base.maxOutput(),
                base.tokenizer(), base.protocol(), 5);

        assertFalse(AgentToolLoopValidator.optionsHash(base)
                .equals(AgentToolLoopValidator.optionsHash(revised)));
    }

    private static ModelSnapshot model() {
        return new ModelSnapshot("01J9Z6Q3W4X5Y6Z7A8B9C0D1E3", "默认", "https://gateway.local/v1",
                "deepseek-chat", "secret", 100_000, 8_000, null, "OPENAI_CHAT", 2);
    }

    private static InvocationResult toolCallResult(String callId, String name, String arguments) {
        return new InvocationResult(callId, null, null, "tool_calls", 1, 100, 1024, "m", "v",
                List.of(new ToolCall("provider-1", name, arguments)));
    }

    private static InvocationResult textResult(String callId, String content) {
        return new InvocationResult(callId, content, null, "stop", 1, 100, 1024, "m", "v");
    }
}
