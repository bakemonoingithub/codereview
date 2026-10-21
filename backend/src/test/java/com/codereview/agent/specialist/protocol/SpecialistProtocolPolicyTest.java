package com.codereview.agent.specialist.protocol;

import com.codereview.agent.api.ApiException;
import com.codereview.agent.api.ErrorCode;
import com.codereview.agent.prompt.PromptInput;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpecialistProtocolPolicyTest {

    @Test
    void allowsTwoExtraFormatRetriesThenStops() {
        assertEquals(3, SpecialistProtocolPolicy.maxAttempts());
        assertTrue(SpecialistProtocolPolicy.shouldRetry(1));
        assertTrue(SpecialistProtocolPolicy.shouldRetry(2));
        assertFalse(SpecialistProtocolPolicy.shouldRetry(3));
        assertFalse(SpecialistProtocolPolicy.shouldRetry(4));
    }

    @Test
    void diagnosticKeepsErrorCodeAndBoundsPreviousResponse() {
        ApiException failure = new ApiException(ErrorCode.MODEL_OUTPUT_INVALID, "bad",
                java.util.Map.of("protocolCode", "UNKNOWN_FIELD"));
        String response = "x".repeat(5000);

        PromptInput.FormatRetry retry = SpecialistProtocolPolicy.diagnostic(2, failure, response);

        assertEquals(2, retry.attempt());
        assertEquals("UNKNOWN_FIELD", retry.errorCode());
        assertEquals(2000, retry.previousResponseExcerpt().length());
    }
}
