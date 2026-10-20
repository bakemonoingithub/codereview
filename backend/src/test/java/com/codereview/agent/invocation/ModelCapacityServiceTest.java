package com.codereview.agent.invocation;

import com.codereview.agent.api.ApiException;
import com.codereview.agent.api.ErrorCode;
import com.codereview.agent.prompt.AssembledPrompt;
import com.codereview.agent.prompt.PromptAssemblyService;
import com.codereview.agent.prompt.PromptInput;
import com.codereview.agent.prompt.PromptMaterial;
import com.codereview.agent.prompt.PromptPurpose;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelCapacityServiceTest {

    private static final String SHA = "b7a312e0c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6";

    private final ModelInvocationProperties properties = new ModelInvocationProperties();
    private final ModelCapacityService service = new ModelCapacityService(properties);
    private final PromptAssemblyService assembly = new PromptAssemblyService();

    @Test
    void reservesOutputAndTenPercentSafety() {
        CapacityEstimate estimate = service.estimate(prompt(PromptPurpose.ISSUE_FILE, 1000),
                model(100_000, 8_000));

        assertEquals(4_096, estimate.outputReserve());
        assertEquals(10_000, estimate.safetyReserve());
        assertEquals(estimate.inputEstimate() + 4_096 + 10_000, estimate.required());
    }

    @Test
    void outputReserveNeverExceedsModelDeclaration() {
        CapacityEstimate estimate = service.estimate(prompt(PromptPurpose.ISSUE_FILE, 1000),
                model(100_000, 2_000));

        assertEquals(2_000, estimate.outputReserve());
    }

    @Test
    void rejectsBeforeSendingWhenRequiredExceedsWindow() {
        ApiException error = assertThrows(ApiException.class,
                () -> service.requireFits(prompt(PromptPurpose.ISSUE_FILE, 64 * 1024),
                        model(10_000, 4_096)));

        assertEquals(ErrorCode.CONTEXT_LIMIT, error.errorCode());
        @SuppressWarnings("unchecked")
        var details = (java.util.Map<String, Object>) error.details();
        assertTrue((Integer) details.get("required") > 10_000);
        assertEquals("ESTIMATED_UTF8_BYTES_V1", details.get("method"));
    }

    @Test
    void missingWindowIsAConfigurationGapNotAnEstimate() {
        ApiException error = assertThrows(ApiException.class,
                () -> service.requireFits(prompt(PromptPurpose.ISSUE_FILE, 100), model(null, 4096)));

        assertEquals(ErrorCode.CONFIG_INCOMPLETE, error.errorCode());
    }

    @Test
    void validationSkipsCapacityButStillUsesTheCommonEstimate() {
        CapacityEstimate estimate = service.estimate(validationPrompt(), model(null, null));

        assertTrue(estimate.fits());
        assertEquals(PromptAssemblyService.ESTIMATE_METHOD, estimate.method());
        assertEquals(64, estimate.outputReserve());
    }

    private AssembledPrompt prompt(PromptPurpose purpose, int contentChars) {
        return assembly.assemble(new PromptInput(purpose, "1", null, null, null,
                "FILES", "main", SHA, null,
                List.of(new PromptMaterial("target-1", "src/Big.java", SHA, "FILE", 1, 1,
                        "x".repeat(contentChars), "COMPLETE", null)),
                List.of(), List.of(), null));
    }

    private AssembledPrompt validationPrompt() {
        return assembly.assemble(new PromptInput(PromptPurpose.VALIDATION, "1", null, null,
                null, "VALIDATION", null, null, null, List.of(), List.of(), List.of(), null));
    }

    private static ModelSnapshot model(Integer window, Integer maxOutput) {
        return new ModelSnapshot("01J9Z6Q3W4X5Y6Z7A8B9C0D1E2", "默认", "https://gateway.local/v1",
                "deepseek-chat", "secret", window, maxOutput, null, "OPENAI_CHAT", 0);
    }
}
