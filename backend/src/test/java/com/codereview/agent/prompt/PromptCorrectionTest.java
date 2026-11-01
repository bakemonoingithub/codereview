package com.codereview.agent.prompt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PAR-04 纠正事件正文基线。
 *
 * <p>期望字符串逐字抄自改造前 {@code AgentTaskWorker} 中的内联文案；这里不复用新实现生成，
 * 重构后不得改写期望值。触发条件、次数、插入位置与消息角色仍由
 * {@code AgentTaskWorkerTest} 覆盖。
 */
class PromptCorrectionTest {

    private final PromptAssemblyService assembly = new PromptAssemblyService();

    @Test
    void correctionTextMatchesTheFormerInlineMessages() {
        assertEquals("上一轮输出不可用（模型返回了无法解析的响应）。请重新给出工具调用或提交结果。",
                assembly.correction(new AgentCorrection.ModelOutputInvalid("模型返回了无法解析的响应")));
        assertEquals("上一轮只有正文、没有工具调用。请二选一：\n"
                        + "- 调用只读工具继续调查；\n"
                        + "- 调用 submit_result 提交结果。\n"
                        + "不要只回复计划或说明。",
                assembly.correction(new AgentCorrection.MissingToolCall()));
        assertEquals("上一轮工具批次不合法：单轮最多 4 个工具；submit_result 必须单独调用，"
                        + "不能与其他工具同批。本批工具没有执行。",
                assembly.correction(new AgentCorrection.InvalidToolBatch(4)));
        assertEquals("上一轮工具调用与返回完全相同，且没有新增材料。请换查询、翻页或结束调查；\n"
                        + "重复同一请求不会得到更多信息。",
                assembly.correction(new AgentCorrection.RepeatedToolCalls()));
    }

    @Test
    void batchLimitIsSubstitutedWithoutChangingTheRestOfTheText() {
        String message = assembly.correction(new AgentCorrection.InvalidToolBatch(7));

        assertTrue(message.contains("单轮最多 7 个工具"), message);
        assertFalse(message.contains("{{"), message);
    }

    @Test
    void diagnosticsAreInsertedVerbatimAndNotRenderedTwice() {
        String message = assembly.correction(
                new AgentCorrection.ModelOutputInvalid("详情含 {{diagnostic}} 字面量"));

        assertEquals("上一轮输出不可用（详情含 {{diagnostic}} 字面量）。请重新给出工具调用或提交结果。", message);
    }

    @Test
    void correctionTemplatesAreRegisteredWithTheirDeclaredVariables() {
        com.codereview.agent.prompt.template.PromptTemplateRegistry registry =
                com.codereview.agent.prompt.template.PromptTemplateRegistry.loadDefault();

        assertEquals(java.util.Set.of("diagnostic"),
                registry.require("correction.MODEL_OUTPUT_INVALID").variables());
        assertEquals(java.util.Set.of(),
                registry.require("correction.MISSING_TOOL_CALL").variables());
        assertEquals(java.util.Set.of("maxToolsPerTurn"),
                registry.require("correction.INVALID_TOOL_BATCH").variables());
        assertThrows(IllegalArgumentException.class,
                () -> registry.render("correction.INVALID_TOOL_BATCH", java.util.Map.of()));
    }
}
