package com.codereview.agent.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/** 模型 v2 请求与视图；验证结果只对同一配置修订有效。 */
public final class ModelDtos {

    private ModelDtos() {
    }

    public record Create(
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Size(max = 500) String baseUrl,
            @NotBlank @Size(max = 200) String modelName,
            @Size(max = 1024) String credential,
            @Min(1) Integer contextWindow,
            @Min(1) Integer maxOutput,
            @Size(max = 100) String tokenizer,
            @Pattern(regexp = "OPENAI_CHAT|OPENAI_RESPONSES", message = "不支持的协议类型")
            String protocol) {
    }

    public record Update(
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Size(max = 500) String baseUrl,
            @NotBlank @Size(max = 200) String modelName,
            @Size(max = 1024) String credential,
            @Min(1) Integer contextWindow,
            @Min(1) Integer maxOutput,
            @Size(max = 100) String tokenizer,
            @Pattern(regexp = "OPENAI_CHAT|OPENAI_RESPONSES", message = "不支持的协议类型")
            String protocol,
            @NotNull Integer expectedRevision) {
    }

    /** 视图带最近一次验证；{@code validationStatus} 为 NONE/EXPIRED/PASSED/FAILED。 */
    public record View(
            String id,
            String name,
            String baseUrl,
            String modelName,
            boolean credentialConfigured,
            Integer contextWindow,
            Integer maxOutput,
            String tokenizer,
            String protocol,
            String validationStatus,
            String agentValidationStatus,
            int revision,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }

    public record ValidationResult(String result, int configRevision, String scope, String detail,
                                   LocalDateTime createdAt) {
    }
}
