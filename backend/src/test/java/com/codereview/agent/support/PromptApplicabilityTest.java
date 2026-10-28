package com.codereview.agent.support;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class PromptApplicabilityTest {
    @ParameterizedTest
    @ValueSource(strings = {"SPECIALIST", "AGENT", "REPORT"})
    void allowsOnlyTheDedicatedPurposeAndCommon(String scene) {
        for (String purpose : new String[]{"SPECIALIST", "AGENT", "REPORT", "COMMON", "UNKNOWN", ""}) {
            assertEquals(purpose.equals(scene) || purpose.equals("COMMON"),
                    PromptApplicability.supports(purpose, scene), purpose + " / " + scene);
        }
        assertFalse(PromptApplicability.supports(null, scene));
        assertFalse(PromptApplicability.supports("COMMON", null));
        assertFalse(PromptApplicability.supports("COMMON", "UNKNOWN"));
        assertFalse(PromptApplicability.supports("COMMON", "COMMON"));
    }
}
