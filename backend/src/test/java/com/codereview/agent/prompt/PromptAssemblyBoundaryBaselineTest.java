package com.codereview.agent.prompt;

import com.codereview.agent.api.ApiException;
import com.codereview.agent.tools.ToolLimitProperties;
import com.codereview.agent.tools.capability.AgentToolCapabilities;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Captured against the PAS-01 source commit before moving production methods. */
class PromptAssemblyBoundaryBaselineTest {
    private static final Path BASELINE = Path.of("src/test/resources/prompt-baseline/pas01-boundaries.json");

    @Test
    void boundaryOutputsKeepTheirOriginalDigests() throws Exception {
        PromptAssemblyService assembly = new PromptAssemblyService();
        ObjectMapper mapper = new ObjectMapper();
        Map<String, String> actual = new LinkedHashMap<>();
        for (int length : List.of(3999, 4000, 4001)) {
            var material = new PromptMaterial("M1", "src/中文.java", "sha", "FILE", 7, null,
                    "class A {}\r\n// {{原样}} 😀\n", "COMPLETE", "诊".repeat(501));
            var input = new PromptInput(PromptPurpose.ISSUE_FILE, "1", "规则", "{{不可展开}}", "注".repeat(length),
                    "FILES", null, "sha", null, List.of(material), List.of(), List.of("缺".repeat(1001)),
                    List.of(), new PromptInput.FormatRetry(1, null, "错".repeat(length - 2000)));
            actual.put("specialist-length-" + length, digest(mapper.writeValueAsBytes(assembly.assemble(input))));
        }
        var tools = AgentToolCapabilities.agent(new ToolLimitProperties());
        var calls = List.of(new ToolCall("c1", "read_code", "{\"path\":\"中文.java\"}"),
                new ToolCall("c2", "tree", "{}"));
        var history = List.of(PromptMessage.assistantToolCalls(calls, null),
                PromptMessage.toolResult("c1", "{{工具原文}}\r\n😀"), PromptMessage.toolResult("c2", "{}"));
        for (int delta : List.of(-1, 0, 1)) {
            var input = new AgentPromptInput("要求", "FILES", "dev", "sha", "描".repeat(2000 + delta),
                    List.of("src/A.java", "src/中文.java"), List.of(new AgentPromptInput.Version("V1", "sha")),
                    List.of(new AgentPromptInput.Comparison("C1", "sha", null)),
                    "导".repeat(16000 + delta), "旧".repeat(20000 + delta));
            actual.put("agent-length-" + delta, digest(mapper.writeValueAsBytes(assembly.assembleAgent(input, history, tools))));
        }
        for (PromptPurpose purpose : PromptPurpose.values()) {
            if (purpose == PromptPurpose.ISSUE_PR) {
                continue;
            }
            actual.put("system-" + purpose, digest(assembly.systemPrompt(purpose).getBytes(StandardCharsets.UTF_8)));
            actual.put("protocol-" + purpose, digest(assembly.protocolBody(purpose).getBytes(StandardCharsets.UTF_8)));
            var empty = new PromptInput(purpose, "1", null, null, null, "VALIDATION", null, null, null,
                    List.of(), List.of(), List.of(), List.of(), null);
            if (purpose.isSpecialist()) {
                ApiException failure = assertThrows(ApiException.class, () -> assembly.assemble(empty));
                actual.put("empty-" + purpose, failure.errorCode().code() + ":" + failure.getMessage());
            } else {
                actual.put("empty-" + purpose, digest(mapper.writeValueAsBytes(assembly.assemble(empty))));
            }
        }
        List<AgentCorrection> events = List.of(new AgentCorrection.ModelOutputInvalid("诊断\n{{原文}}"),
                new AgentCorrection.MissingToolCall(), new AgentCorrection.InvalidToolBatch(3),
                new AgentCorrection.RepeatedToolCalls());
        for (AgentCorrection event : events) {
            actual.put("correction-" + event.getClass().getSimpleName(),
                    digest(assembly.correction(event).getBytes(StandardCharsets.UTF_8)));
        }
        if (Boolean.getBoolean("pas.captureBoundaryBaseline")) {
            assertFalse(Files.exists(BASELINE), "Refuse to overwrite original boundary expectations");
            Files.writeString(BASELINE, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(actual) + "\n");
        }
        assertEquals(mapper.readTree(Files.readString(BASELINE)), mapper.valueToTree(actual),
                "Keep original digests; reproduce the same input against the before commit to diagnose a mismatch");
    }

    private static String digest(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
