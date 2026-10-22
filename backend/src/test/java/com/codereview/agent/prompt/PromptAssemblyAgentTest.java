package com.codereview.agent.prompt;

import com.codereview.agent.tools.AgentToolSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptAssemblyAgentTest {

    private final PromptAssemblyService assembly = new PromptAssemblyService();
    private final List<Map<String, Object>> tools = AgentToolSchema.definitions();

    @Test
    void firstTurnCarriesRequirementsScopeVersionsAndTools() {
        AssembledPrompt prompt = assembly.assembleAgent(input(), List.of(), tools);

        assertEquals(PromptPurpose.AGENT, prompt.purpose());
        assertEquals(2, prompt.messages().size());
        assertEquals("system", prompt.messages().get(0).role());
        assertEquals("user", prompt.messages().get(1).role());
        assertTrue(prompt.hasTools());
        assertEquals("auto", prompt.toolChoice());
        String user = prompt.messages().get(1).content();
        assertTrue(user.contains("## 本次要求"));
        assertTrue(user.contains("检查重试链路"));
        assertTrue(user.contains("V1 = " + sha(1)));
        assertTrue(user.contains("C1"));
        assertTrue(user.contains("根提交"));
        assertTrue(prompt.systemPrompt().contains("只读工具"));
        assertTrue(user.contains("submit_result"));
    }

    @Test
    void priorityTextKeepsRequirementsAheadOfGuidance() {
        AssembledPrompt prompt = assembly.assembleAgent(input(), List.of(), tools);
        String user = prompt.messages().get(1).content();

        int requirements = user.indexOf("## 本次要求");
        int guidance = user.indexOf("## 指导提示词");
        assertTrue(requirements >= 0 && guidance > requirements, user);
        assertTrue(prompt.systemPrompt().contains("2. 用户消息中的本次要求"));
        assertTrue(prompt.systemPrompt().contains("3. 用户消息中的指导提示词"));
    }

    @Test
    void followUpReplaysToolHistoryWithoutRewritingIt() {
        ToolCall call = new ToolCall("provider-1", "read_code",
                "{\"version\":\"V1\",\"path\":\"src/A.java\"}");
        List<PromptMessage> history = List.of(
                PromptMessage.assistantToolCalls(List.of(call), null),
                PromptMessage.toolResult("provider-1", "{\"status\":\"OK\"}"));

        AssembledPrompt prompt = assembly.assembleAgent(input(), history, tools);

        assertEquals(4, prompt.messages().size());
        assertEquals("assistant", prompt.messages().get(2).role());
        assertEquals(1, prompt.messages().get(2).toolCalls().size());
        assertEquals("tool", prompt.messages().get(3).role());
        assertEquals("provider-1", prompt.messages().get(3).toolCallId());
        assertTrue(prompt.inputEstimate() > 0);
    }

    @Test
    void toolDefinitionsAndHistoryCountTowardsTheEstimate() {
        AssembledPrompt withoutHistory = assembly.assembleAgent(input(), List.of(), tools);
        AssembledPrompt withHistory = assembly.assembleAgent(input(),
                List.of(PromptMessage.toolResult("p1", "x".repeat(2000))), tools);

        assertTrue(withoutHistory.inputEstimate() > 3000);
        assertTrue(withHistory.inputEstimate() > withoutHistory.inputEstimate());
        assertFalse(withoutHistory.messages().isEmpty());
    }

    @Test
    void optionalSectionsAreOmittedWhenAbsent() {
        AgentPromptInput bare = new AgentPromptInput("要求", "TEXT", null, null, null,
                List.of(), List.of(), null, null);

        AssembledPrompt prompt = assembly.assembleAgent(bare, List.of(), tools);

        String user = prompt.messages().get(1).content();
        assertFalse(user.contains("## 指导提示词"));
        assertFalse(user.contains("## 旧结果线索"));
        assertTrue(user.contains("范围类型：TEXT"));
    }

    private static AgentPromptInput input() {
        return new AgentPromptInput("检查重试链路，并说明材料缺口。", "COMMITS", null, null,
                "两次提交的重试改动",
                List.of(new AgentPromptInput.Version("V1", sha(1)),
                        new AgentPromptInput.Version("V2", sha(2))),
                List.of(new AgentPromptInput.Comparison("C1", sha(2), null)),
                "优先看调用方。", "{\"summary\":\"旧结论\"}");
    }

    private static String sha(int suffix) {
        return "b7a312e0c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f" + suffix;
    }
}
