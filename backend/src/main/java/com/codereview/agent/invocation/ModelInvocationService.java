package com.codereview.agent.invocation;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.codereview.agent.api.ApiException;
import com.codereview.agent.api.ErrorCode;
import com.codereview.agent.db.AgentDatabase;
import com.codereview.agent.entity.AgentModelCall;
import com.codereview.agent.entity.AgentTaskQuota;
import com.codereview.agent.execution.TaskExecutionGuard;
import com.codereview.agent.mapper.AgentModelCallMapper;
import com.codereview.agent.mapper.AgentTaskQuotaMapper;
import com.codereview.agent.prompt.AssembledPrompt;
import com.codereview.agent.prompt.PromptAssemblyService;
import com.codereview.agent.prompt.PromptInput;
import com.codereview.agent.prompt.PromptPurpose;
import com.codereview.agent.prompt.ToolCall;
import com.codereview.agent.support.AgentIds;
import com.codereview.agent.support.AgentTime;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 公共模型调用入口。
 *
 * <p>顺序固定：容量判断 → 额度原子预留 → 请求正文落盘 → 发请求 → 记录结果。
 * 请求正文保存失败时绝不发请求；每次真实网络尝试各有一条 {@code model_call}，
 * 所有尝试都计入任务额度。暂态失败最多额外重试一次，鉴权、配置和协议错误不重试。
 *
 * <p>调用层只负责传输与分类；协议结构校验、格式重试和结果发布由专项执行层负责。
 */
@Service
@ConditionalOnProperty(prefix = "agent.datasource", name = "url")
public class ModelInvocationService {

    private final AgentDatabase db;
    private final ObjectMapper mapper;
    private final ModelTransport transport;
    private final ModelCapacityService capacity;
    private final PromptAssemblyService assembly;
    private final ModelInvocationProperties properties;
    private final ModelCallConcurrencyLimiter limiter;
    private final TaskExecutionGuard guard;

    public ModelInvocationService(AgentDatabase db, ObjectMapper mapper, ModelTransport transport,
                                  ModelCapacityService capacity, PromptAssemblyService assembly,
                                  ModelCallConcurrencyLimiter limiter,
                                  ModelInvocationProperties properties, TaskExecutionGuard guard) {
        this.db = db;
        this.mapper = mapper;
        this.transport = transport;
        this.capacity = capacity;
        this.assembly = assembly;
        this.limiter = limiter;
        this.properties = properties;
        this.guard = guard;
    }

    public InvocationResult invoke(InvocationRequest request) {
        ModelSnapshot model = request.model();
        AssembledPrompt prompt = request.prompt();
        InvocationScope scope = request.scope();
        requireModel(model);

        CapacityEstimate estimate = request.sendWholeSpecialist()
                ? capacity.estimate(prompt, model, request.outputReserve())
                : capacity.requireFits(prompt, model, request.outputReserve());
        String body = requestBody(model, prompt, estimate.outputReserve());
        int attempts = 1 + Math.max(0, properties.getTransportRetries());
        ApiException lastFailure = null;

        for (int attempt = 1; attempt <= attempts; attempt++) {
            boolean last = attempt == attempts;
            scope = activeScope(scope);
            timeoutFor(scope, request.timeoutSeconds());
            ModelCallConcurrencyLimiter.Permit permit = limiter.acquire(scope.taskId(), scope.deadlineAt());
            try {
                scope = activeScope(scope);
                timeoutFor(scope, request.timeoutSeconds());
                if (scope.taskId() != null) {
                    reserveQuota(scope.taskId());
                }
                AgentModelCall call = startCall(scope, request.reportId(), request.turnNo(), prompt, body, attempt, estimate);
                Duration timeout;
                try {
                    // 等待许可和请求落盘都可能跨过取消或期限；以真正发送前的状态为准。
                    scope = activeScope(scope);
                    timeout = timeoutFor(scope, request.timeoutSeconds());
                } catch (ApiException stopped) {
                    finishFailure(call, stopped, null);
                    throw stopped;
                }
                try {
                    ModelTransport.TransportResponse response = transport.post(endpoint(model),
                            model.credential(), body, timeout);
                    if (response.status() >= 200 && response.status() < 300) {
                        return succeed(call, response, estimate, attempt);
                    }
                    ApiException failure = classify(response);
                    finishFailure(call, failure, response.body());
                    lastFailure = failure;
                    if (!failure.errorCode().retryable() || last) {
                        throw failure;
                    }
                } catch (ModelTransport.TransportFailure failure) {
                    ApiException mapped = new ApiException(
                            failure.timeout() ? ErrorCode.UPSTREAM_TIMEOUT : ErrorCode.MODEL_UNAVAILABLE,
                            failure.getMessage(), Map.of("attempt", attempt));
                    finishFailure(call, mapped, null);
                    lastFailure = mapped;
                    if (last) {
                        throw mapped;
                    }
                }
            } finally {
                permit.close();
            }
            scope = activeScope(scope);
            waitBeforeRetry(failureRetryAfter(lastFailure), scope);
        }
        throw lastFailure == null
                ? new ApiException(ErrorCode.INTERNAL_ERROR, "模型调用结束但没有结果")
                : lastFailure;
    }

    /**
     * 连通验证也走同一组装与调用入口：保存实际请求、计量并记录尝试，
     * 不再用另一套探针重建模板。结果本身是「通过／失败」，不抛 4xx。
     */
    public ValidationOutcome validate(ModelSnapshot model) {
        AssembledPrompt prompt = assembly.assemble(new PromptInput(PromptPurpose.VALIDATION, "1",
                null, null, null, "VALIDATION", null, null, null,
                List.of(), List.of(), List.of(), List.of(), null));
        try {
            InvocationResult result = invoke(new InvocationRequest(model, prompt,
                    InvocationScope.standalone(AgentTime.now().plusMinutes(1))));
            if (result.content() == null || result.content().isBlank()) {
                return new ValidationOutcome(false, "模型返回正文为空", result.callId());
            }
            try {
                JsonNode root = mapper.readTree(result.content());
                if (root.isObject() && root.path("ok").isBoolean() && root.path("ok").booleanValue()) {
                    return new ValidationOutcome(true, "连通成功，模型返回了最小 JSON", result.callId());
                }
                return new ValidationOutcome(false, "模型返回的 JSON 缺少 ok=true", result.callId());
            } catch (JsonProcessingException e) {
                return new ValidationOutcome(false, "模型返回的不是合法 JSON", result.callId());
            }
        } catch (ApiException e) {
            return new ValidationOutcome(false, e.getMessage(), null);
        }
    }

    private AgentModelCall startCall(InvocationScope scope, String reportId, Integer turnNo,
                                     AssembledPrompt prompt,
                                     String body, int attempt, CapacityEstimate estimate) {
        LocalDateTime now = AgentTime.now();
        AgentModelCall call = new AgentModelCall();
        call.setId(AgentIds.next());
        call.setTaskId(scope.taskId());
        call.setReportId(reportId);
        call.setTargetId(scope.targetId());
        call.setUnitId(scope.unitId());
        call.setPurpose(prompt.purpose().wireName());
        call.setAttempt(attempt);
        call.setTurnNo(turnNo);
        call.setState("RUNNING");
        call.setRequestBody(body);
        call.setAdapterVersion(prompt.adapterVersion());
        call.setEstimateMethod(estimate.method());
        call.setCreatedAt(now);
        call.setStartedAt(now);
        db.mapper(AgentModelCallMapper.class).insert(call);
        return call;
    }

    private InvocationResult succeed(AgentModelCall call, ModelTransport.TransportResponse response,
                                     CapacityEstimate estimate, int attempt) {
        JsonNode root;
        try {
            root = mapper.readTree(response.body());
        } catch (JsonProcessingException e) {
            ApiException failure = new ApiException(ErrorCode.MODEL_OUTPUT_INVALID,
                    "模型返回的不是 JSON", Map.of("callId", call.getId()));
            finishFailure(call, failure, response.body());
            throw failure;
        }
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            ApiException failure = new ApiException(ErrorCode.MODEL_OUTPUT_INVALID,
                    "模型响应缺少 choices", Map.of("callId", call.getId()));
            finishFailure(call, failure, response.body());
            throw failure;
        }
        JsonNode first = choices.get(0);
        String finishReason = first.path("finish_reason").asText(null);
        if ("length".equals(finishReason)) {
            ApiException failure = new ApiException(ErrorCode.MODEL_OUTPUT_INVALID,
                    "模型输出因长度限制被截断", Map.of("callId", call.getId(),
                            "finishReason", finishReason));
            finishFailure(call, failure, response.body());
            throw failure;
        }
        String content = textOf(first.path("message").path("content"));
        List<ToolCall> toolCalls = toolCallsOf(first.path("message").path("tool_calls"), call.getId());
        if ((content == null || content.isBlank()) && toolCalls.isEmpty()) {
            ApiException failure = new ApiException(ErrorCode.MODEL_OUTPUT_INVALID,
                    "模型响应缺少正文", Map.of("callId", call.getId()));
            finishFailure(call, failure, response.body());
            throw failure;
        }
        JsonNode usage = root.path("usage").isObject() ? root.path("usage") : null;
        call.setState("SUCCEEDED");
        call.setResponseBody(response.body());
        call.setUsageJson(usage == null ? null : usage.toString());
        call.setFinishedAt(AgentTime.now());
        db.mapper(AgentModelCallMapper.class).updateById(call);
        return new InvocationResult(call.getId(), content, usage, finishReason, attempt,
                estimate.inputEstimate(), estimate.outputReserve(), estimate.method(),
                call.getAdapterVersion(), toolCalls);
    }

    /**
     * 解析原生工具调用：只接受 id/name/arguments 三个字段，同一响应内 id 不能重复。
     *
     * <p>参数原文保留，由工具层严格校验；这里只拒绝结构性错误，避免把半截协议交给执行器。
     */
    private List<ToolCall> toolCallsOf(JsonNode node, String callId) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new ApiException(ErrorCode.MODEL_OUTPUT_INVALID, "tool_calls 不是数组",
                    Map.of("callId", callId));
        }
        List<ToolCall> calls = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (JsonNode item : node) {
            String id = item.path("id").asText(null);
            JsonNode function = item.path("function");
            String name = function.path("name").asText(null);
            JsonNode arguments = function.path("arguments");
            String rawArguments = arguments.isTextual() ? arguments.asText()
                    : arguments.isMissingNode() ? "{}" : arguments.toString();
            if (id == null || id.isBlank() || name == null || name.isBlank()) {
                throw new ApiException(ErrorCode.MODEL_OUTPUT_INVALID,
                        "工具调用缺少 id 或 name", Map.of("callId", callId));
            }
            if (ids.contains(id)) {
                throw new ApiException(ErrorCode.MODEL_OUTPUT_INVALID,
                        "同一响应内的工具调用 id 重复", Map.of("callId", callId, "toolCallId", id));
            }
            ids.add(id);
            calls.add(new ToolCall(id, name, rawArguments));
        }
        return List.copyOf(calls);
    }

    private void finishFailure(AgentModelCall call, ApiException failure, String responseBody) {
        call.setState("FAILED");
        call.setResponseBody(responseBody);
        call.setErrorCode(failure.errorCode().code());
        call.setErrorMessage(truncate(failure.getMessage(), 1000));
        call.setFinishedAt(AgentTime.now());
        db.mapper(AgentModelCallMapper.class).updateById(call);
    }

    private String requestBody(ModelSnapshot model, AssembledPrompt prompt, int outputReserve) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model.modelName());
        body.put("temperature", 0);
        body.put("max_tokens", outputReserve);
        body.put("stream", false);
        if (requiresPlainOutputBudget(model, prompt)) {
            // DeepSeek defaults to thinking mode; its reasoning tokens share max_tokens with
            // the final answer and can consume the entire output budget. Validation uses the
            // same small budget as its JSON answer, so thinking must be off there as well.
            body.put("reasoning_effort", "none");
        }
        if (prompt.jsonMode()) {
            body.putObject("response_format").put("type", "json_object");
        }
        ArrayNode messages = body.putArray("messages");
        for (var message : prompt.messages()) {
            ObjectNode node = messages.addObject().put("role", message.role());
            if (message.content() != null) {
                node.put("content", message.content());
            } else if (!message.toolCalls().isEmpty()) {
                // assistant 只带工具调用时正文为空；显式 null 与 OpenAI 兼容协议一致。
                node.putNull("content");
            }
            if (!message.toolCalls().isEmpty()) {
                ArrayNode calls = node.putArray("tool_calls");
                for (ToolCall call : message.toolCalls()) {
                    ObjectNode item = calls.addObject();
                    item.put("id", call.id());
                    item.put("type", "function");
                    ObjectNode function = item.putObject("function");
                    function.put("name", call.name());
                    function.put("arguments", call.arguments());
                }
            }
            if (message.toolCallId() != null) {
                node.put("tool_call_id", message.toolCallId());
            }
        }
        if (prompt.hasTools()) {
            body.set("tools", mapper.valueToTree(prompt.tools()));
            body.put("tool_choice", prompt.toolChoice() == null || prompt.toolChoice().isBlank()
                    ? "auto" : prompt.toolChoice());
        }
        try {
            return mapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "请求正文序列化失败");
        }
    }

    private ApiException classify(ModelTransport.TransportResponse response) {
        int status = response.status();
        String body = response.body() == null ? "" : response.body();
        if (status == 401 || status == 403) {
            return new ApiException(ErrorCode.MODEL_UNAUTHORIZED, "模型凭据被拒绝",
                    Map.of("status", status));
        }
        if (status == 429) {
            Long retryAfter = retryAfterSeconds(response);
            return new ApiException(ErrorCode.RATE_LIMITED, "模型网关限流",
                    retryAfter == null ? Map.of("status", status)
                            : Map.of("status", status, "retryAfter", retryAfter));
        }
        if (status >= 500) {
            return new ApiException(ErrorCode.MODEL_UNAVAILABLE, "模型网关暂时不可用",
                    Map.of("status", status));
        }
        if (status == 400 && looksLikeContextLimit(body)) {
            return new ApiException(ErrorCode.CONTEXT_LIMIT, "模型报告上下文超限",
                    Map.of("status", status));
        }
        return new ApiException(ErrorCode.MODEL_BAD_REQUEST, "模型请求被拒绝",
                Map.of("status", status));
    }

    static boolean looksLikeContextLimit(String body) {
        try {
            JsonNode error = new ObjectMapper().readTree(body).path("error");
            return List.of("context_length_exceeded", "context_window_exceeded")
                    .contains(error.path("code").asText());
        } catch (Exception invalid) {
            return false;
        }
    }

    private InvocationScope activeScope(InvocationScope scope) {
        if (scope.taskId() == null) {
            return scope;
        }
        var task = guard.requireActive(scope.taskId());
        LocalDateTime deadline = scope.deadlineAt();
        if (task.getDeadlineAt() != null && (deadline == null || task.getDeadlineAt().isBefore(deadline))) {
            deadline = task.getDeadlineAt();
        }
        return new InvocationScope(scope.taskId(), scope.targetId(), scope.unitId(), deadline);
    }

    private Duration timeoutFor(InvocationScope scope, Integer requestedTimeoutSeconds) {
        Duration limit = Duration.ofSeconds(properties.getMaxReadTimeoutSeconds());
        if (requestedTimeoutSeconds != null) {
            limit = Duration.ofSeconds(Math.min(limit.getSeconds(), requestedTimeoutSeconds));
        }
        if (scope.deadlineAt() == null) {
            return limit;
        }
        Duration remaining = Duration.between(AgentTime.now(), scope.deadlineAt());
        if (remaining.isNegative() || remaining.isZero()) {
            throw new ApiException(ErrorCode.TASK_DEADLINE, "任务已到达期限，未发送模型请求");
        }
        Duration capped = remaining.minusSeconds(1);
        if (capped.isNegative() || capped.isZero()) {
            throw new ApiException(ErrorCode.TASK_DEADLINE, "任务剩余期限不足一次调用");
        }
        return capped.compareTo(limit) < 0 ? capped : limit;
    }

    private void waitBeforeRetry(Long retryAfterSeconds, InvocationScope scope) {
        long wait = retryAfterSeconds != null
                ? Math.min(retryAfterSeconds * 1000, 3000)
                : properties.getRetryBaseMillis();
        if (wait <= 0) {
            wait = 1;
        }
        if (scope.deadlineAt() != null) {
            long remaining = Duration.between(AgentTime.now(), scope.deadlineAt()).toMillis();
            if (remaining <= wait + 500) {
                throw new ApiException(ErrorCode.TASK_DEADLINE, "任务剩余期限不足以重试");
            }
        }
        try {
            Thread.sleep(wait);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(ErrorCode.MODEL_UNAVAILABLE, "模型重试被中断");
        }
    }

    private static Long failureRetryAfter(ApiException failure) {
        if (failure != null && failure.details() instanceof Map<?, ?> details
                && details.get("retryAfter") instanceof Number number) {
            return number.longValue();
        }
        return null;
    }

    private static Long retryAfterSeconds(ModelTransport.TransportResponse response) {
        String value = response.headers() == null ? null : response.headers().get("retry-after");
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void reserveQuota(String taskId) {
        AgentTaskQuotaMapper quotaMapper = db.mapper(AgentTaskQuotaMapper.class);
        int updated = quotaMapper.update(null, new LambdaUpdateWrapper<AgentTaskQuota>()
                .eq(AgentTaskQuota::getTaskId, taskId)
                .apply("reserved < hard_limit")
                .setSql("reserved = reserved + 1")
                .set(AgentTaskQuota::getUpdatedAt, AgentTime.now()));
        if (updated > 0) {
            return;
        }
        if (quotaMapper.selectById(taskId) == null) {
            LocalDateTime now = AgentTime.now();
            AgentTaskQuota quota = new AgentTaskQuota();
            quota.setTaskId(taskId);
            quota.setHardLimit(properties.getTaskCallLimit());
            quota.setReserved(1);
            quota.setCreatedAt(now);
            quota.setUpdatedAt(now);
            try {
                quotaMapper.insert(quota);
                return;
            } catch (DuplicateKeyException race) {
                if (quotaMapper.update(null, new LambdaUpdateWrapper<AgentTaskQuota>()
                        .eq(AgentTaskQuota::getTaskId, taskId)
                        .apply("reserved < hard_limit")
                        .setSql("reserved = reserved + 1")
                        .set(AgentTaskQuota::getUpdatedAt, now)) > 0) {
                    return;
                }
            }
        }
        throw new ApiException(ErrorCode.QUOTA_EXCEEDED,
                "任务请求额度已用尽（上限 " + properties.getTaskCallLimit() + " 次）",
                Map.of("taskId", taskId));
    }

    private void requireModel(ModelSnapshot model) {
        if (model.baseUrl() == null || model.baseUrl().isBlank()) {
            throw new ApiException(ErrorCode.CONFIG_INCOMPLETE, "模型接口地址为空",
                    Map.of("modelId", model.id()));
        }
        if (model.modelName() == null || model.modelName().isBlank()) {
            throw new ApiException(ErrorCode.CONFIG_INCOMPLETE, "模型名称为空",
                    Map.of("modelId", model.id()));
        }
        if (!model.openAiCompatible()) {
            throw new ApiException(ErrorCode.CONFIG_INCOMPLETE,
                    "暂不支持该模型协议：" + model.protocol(), Map.of("modelId", model.id()));
        }
    }

    private static String endpoint(ModelSnapshot model) {
        String base = model.baseUrl().trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base.endsWith("/chat/completions") ? base : base + "/chat/completions";
    }

    private static boolean isDeepSeekModel(ModelSnapshot model) {
        return model.modelName() != null
                && model.modelName().trim().toLowerCase(java.util.Locale.ROOT).startsWith("deepseek");
    }

    /**
     * 结构化调用和验证调用都要求模型直接给出最终答案；这两类调用必须关闭 DeepSeek 的
     * 默认思考模式，否则推理 token 会吃掉本就不大的输出预算。报告和 Agent 调用不在此列，
     * 保留其按需思考的行为。
     */
    private static boolean requiresPlainOutputBudget(ModelSnapshot model, AssembledPrompt prompt) {
        return isDeepSeekModel(model)
                && (prompt.jsonMode() || prompt.purpose() == PromptPurpose.VALIDATION
                || prompt.purpose() == PromptPurpose.AGENT_TOOL_CHECK);
    }

    private static String textOf(JsonNode content) {
        if (content == null || content.isMissingNode() || content.isNull()) {
            return null;
        }
        if (content.isTextual()) {
            return content.asText();
        }
        if (content.isArray()) {
            StringBuilder text = new StringBuilder();
            for (JsonNode part : content) {
                if (part.path("text").isTextual()) {
                    text.append(part.path("text").asText());
                }
            }
            return text.toString();
        }
        return content.asText();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** 验证动作的结果；失败也作为一次成功执行的验证记录返回。 */
    public record ValidationOutcome(boolean passed, String detail, String callId) {
    }
}
