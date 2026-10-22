package com.codereview.agent.specialist.protocol;

import com.codereview.agent.api.ApiException;
import com.codereview.agent.prompt.PromptInput;

/**
 * 格式重试策略：初次输出加最多 2 次额外重试（共 3 次尝试）。
 *
 * <p>只对可观察的输出协议错误生效；上下文超限、取消、期限和传输错误按各自规则处理，
 * 不能借格式重试绕过请求额度。每次尝试仍由公共调用入口记录。
 */
public final class SpecialistProtocolPolicy {

    public static final int MAX_FORMAT_RETRIES = 2;
    static final int MAX_EXCERPT = 2000;

    private SpecialistProtocolPolicy() {
    }

    public static int maxAttempts() {
        return 1 + MAX_FORMAT_RETRIES;
    }

    /** {@code failedAttempt} 是刚失败的尝试序号；第 3 次失败后不再重试。 */
    public static boolean shouldRetry(int failedAttempt) {
        return failedAttempt >= 1 && failedAttempt <= MAX_FORMAT_RETRIES;
    }

    public static PromptInput.FormatRetry diagnostic(int failedAttempt, ApiException failure,
                                                     String previousResponse) {
        String code = "PROTOCOL_INVALID";
        if (failure != null && failure.details() instanceof java.util.Map<?, ?> details
                && details.get("protocolCode") instanceof String protocolCode
                && !protocolCode.isBlank()) {
            code = protocolCode;
        } else if (failure != null && failure.errorCode() != null) {
            code = failure.errorCode().code();
        }
        return new PromptInput.FormatRetry(failedAttempt, code, excerpt(previousResponse));
    }

    static String excerpt(String response) {
        if (response == null) {
            return null;
        }
        return response.length() <= MAX_EXCERPT ? response : response.substring(0, MAX_EXCERPT);
    }
}
