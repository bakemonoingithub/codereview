package com.codereview.agent.api;

import com.codereview.agent.dto.PromptDtos;
import jakarta.validation.Validation;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PromptPurposeValidationTest {
    @ParameterizedTest
    @ValueSource(strings = {"SPECIALIST", "AGENT", "REPORT", "COMMON", "UNKNOWN", "common", ""})
    void validatesBothCreateAndUpdatePurpose(String purpose) {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            boolean allowed = List.of("SPECIALIST", "AGENT", "REPORT", "COMMON").contains(purpose);
            assertEquals(allowed, validator.validate(new PromptDtos.Create(
                    "规则", null, List.of(), purpose, "规则正文")).isEmpty());
            assertEquals(allowed, validator.validate(new PromptDtos.Update(
                    "规则", null, List.of(), purpose, 0)).isEmpty());
        }
    }
}
