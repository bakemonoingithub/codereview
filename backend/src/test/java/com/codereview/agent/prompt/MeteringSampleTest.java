package com.codereview.agent.prompt;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 计量基线样本：4 KiB 单文件、无策略、单目标。
 *
 * <p>固定字节数与估算值，防止组装模板、行号或封装余量变化后计量悄悄漂移。
 * 该数字是 UTF-8 字节上界估算，不是真实 tokenizer 或模型 usage。
 */
class PromptMeteringSampleTest {

    private static final String SHA = "b7a312e0c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6";

    @Test
    void fourKibSingleFileKeepsAStableEstimatedBaseline() {
        AssembledPrompt prompt = new PromptAssemblyService().assemble(new PromptInput(
                PromptPurpose.ISSUE_FILE, "1", null, null, null, "FILES", "main", SHA, null,
                List.of(new PromptMaterial("target-1", "src/Sample.java", SHA, "FILE", 1, 1,
                        "x".repeat(4096), "COMPLETE", null)),
                List.of(), List.of(), null));

        assertEquals(6229, prompt.inputBytes());
        assertEquals(6325, prompt.inputEstimate());
        assertEquals("ESTIMATED_UTF8_BYTES_V1", prompt.estimateMethod());
        assertTrue(prompt.inputEstimate() > 4096);
    }
}
