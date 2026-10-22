package com.codereview.agent.invocation;

import com.codereview.agent.api.ApiException;
import com.codereview.agent.api.ErrorCode;
import com.codereview.agent.db.AgentDatabase;
import com.codereview.agent.entity.AgentModelCall;
import com.codereview.agent.mapper.AgentModelCallMapper;
import com.codereview.agent.prompt.AssembledPrompt;
import com.codereview.agent.prompt.PromptAssemblyService;
import com.codereview.agent.tools.AgentToolSchema;
import com.codereview.agent.tools.AgentToolScope;
import com.codereview.agent.tools.ToolArgumentParser;
import com.codereview.agent.tools.ToolInvocation;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 原生工具协议适配：请求体形状、空正文工具响应、工具调用解析与计量。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ModelToolProtocolTest {

    private static final List<Map<String, Object>> TOOLS = AgentToolSchema.definitions();

    @Mock
    private AgentDatabase db;
    @Mock
    private AgentModelCallMapper callMapper;
    @Mock
    private ModelTransport transport;

    private ModelInvocationService service;
    private ModelInvocationProperties properties;
    private final PromptAssemblyService assembly = new PromptAssemblyService();

    @BeforeEach
    void setUp() {
        properties = new ModelInvocationProperties();
        service = new ModelInvocationService(db, new com.fasterxml.jackson.databind.ObjectMapper(),
                transport, new ModelCapacityService(properties), assembly,
                new ModelCallConcurrencyLimiter(properties), properties,
                new com.codereview.agent.execution.TaskExecutionGuard(db));
        when(db.mapper(AgentModelCallMapper.class)).thenReturn(callMapper);
        when(callMapper.insert(any(AgentModelCall.class))).thenReturn(1);
        when(callMapper.updateById(any(AgentModelCall.class))).thenReturn(1);
    }

    @Test
    void requestContainsToolsToolChoiceAndToolHistory() {
        when(transport.post(any(), any(), any(), any())).thenReturn(ok(textResponse("收到")));

        AssembledPrompt prompt = assembly.assembleToolCheckFollowUp(TOOLS,
                new com.codereview.agent.prompt.ToolCall("call_1", "read_code",
                        "{\"version\":\"V1\",\"path\":\"fixture/Fixture.java\"}"),
                "{\"status\":\"OK\"}");
        service.invoke(new InvocationRequest(model(), prompt, standalone()));

        ArgumentCaptor<AgentModelCall> saved = ArgumentCaptor.forClass(AgentModelCall.class);
        verify(callMapper).insert(saved.capture());
        String body = saved.getValue().getRequestBody();
        assertTrue(body.contains("\"tools\""), body);
        assertTrue(body.contains("\"tool_choice\":\"auto\""), body);
        assertTrue(body.contains("\"tool_calls\""), body);
        assertTrue(body.contains("\"tool_call_id\":\"call_1\""), body);
        assertTrue(body.contains("\"role\":\"tool\""), body);
        assertTrue(body.contains("reasoning_effort"), "工具协议验证应关闭思考模式");
        assertFalse(body.contains("secret"));
    }

    @Test
    void toolCallWithEmptyContentIsAcceptedAndParsed() {
        when(transport.post(any(), any(), any(), any())).thenReturn(ok(toolResponse()));

        InvocationResult result = service.invoke(new InvocationRequest(model(),
                assembly.assembleToolCheck(TOOLS), standalone()));

        assertEquals(1, result.toolCalls().size());
        assertEquals("read_code", result.toolCalls().get(0).name());
        assertEquals("call_1", result.toolCalls().get(0).id());
        assertTrue(result.content() == null || result.content().isBlank());

        ToolInvocation parsed = new ToolArgumentParser(new com.fasterxml.jackson.databind.ObjectMapper())
                .parse("read_code", result.toolCalls().get(0).arguments(),
                        AgentToolScope.single("p1", "b7a312e0c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6"));
        assertEquals("fixture/Fixture.java", parsed.path());
    }

    @Test
    void duplicateToolCallIdsInOneResponseAreRejected() {
        when(transport.post(any(), any(), any(), any())).thenReturn(ok("""
                {"choices":[{"message":{"content":null,"tool_calls":[
                  {"id":"call_1","type":"function","function":{"name":"read_code","arguments":"{}"}},
                  {"id":"call_1","type":"function","function":{"name":"tree","arguments":"{}"}}
                ]},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":1,"completion_tokens":1}}
                """));

        ApiException error = assertThrows(ApiException.class, () -> service.invoke(
                new InvocationRequest(model(), assembly.assembleToolCheck(TOOLS), standalone())));

        assertEquals(ErrorCode.MODEL_OUTPUT_INVALID, error.errorCode());
    }

    @Test
    void neitherContentNorToolCallsIsStillAnInvalidResponse() {
        when(transport.post(any(), any(), any(), any())).thenReturn(ok("""
                {"choices":[{"message":{"content":null},"finish_reason":"stop"}],
                 "usage":{"prompt_tokens":1,"completion_tokens":0}}
                """));

        ApiException error = assertThrows(ApiException.class, () -> service.invoke(
                new InvocationRequest(model(), assembly.assembleToolCheck(TOOLS), standalone())));

        assertEquals(ErrorCode.MODEL_OUTPUT_INVALID, error.errorCode());
    }

    @Test
    void toolDefinitionsCountTowardsTheInputEstimate() {
        AssembledPrompt prompt = assembly.assembleToolCheck(TOOLS);

        assertTrue(prompt.hasTools());
        assertEquals(2, prompt.messages().size());
        assertTrue(prompt.inputEstimate() > 3000, "estimate=" + prompt.inputEstimate());

        AssembledPrompt followUp = assembly.assembleToolCheckFollowUp(TOOLS,
                new com.codereview.agent.prompt.ToolCall("call_1", "read_code", "{}"), "{}");
        assertEquals(4, followUp.messages().size());
        assertTrue(followUp.inputEstimate() > prompt.inputEstimate());
    }

    private static ModelSnapshot model() {
        return new ModelSnapshot("01J9Z6Q3W4X5Y6Z7A8B9C0D1E3", "默认", "https://gateway.local/v1",
                "deepseek-chat", "secret", 100_000, 8_000, null, "OPENAI_CHAT", 0);
    }

    private static InvocationScope standalone() {
        return InvocationScope.standalone(com.codereview.agent.support.AgentTime.now().plusMinutes(2));
    }

    private static ModelTransport.TransportResponse ok(String body) {
        return new ModelTransport.TransportResponse(200, body, Map.of(), 5);
    }

    private static String textResponse(String text) {
        return "{\"choices\":[{\"message\":{\"content\":\"收到第 1 到 3 行\"},"
                + "\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}";
    }

    private static String toolResponse() {
        return """
                {"choices":[{"message":{"content":null,"tool_calls":[
                  {"id":"call_1","type":"function","function":{"name":"read_code",
                   "arguments":"{\\"version\\":\\"V1\\",\\"path\\":\\"fixture/Fixture.java\\"}"}}
                ]},"finish_reason":"tool_calls"}],
                 "usage":{"prompt_tokens":10,"completion_tokens":5}}
                """;
    }
}
