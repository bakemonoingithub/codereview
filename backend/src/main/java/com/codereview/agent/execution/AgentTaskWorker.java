package com.codereview.agent.execution;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.codereview.agent.api.ApiException;
import com.codereview.agent.api.ErrorCode;
import com.codereview.agent.db.AgentDatabase;
import com.codereview.agent.entity.AgentCallMaterial;
import com.codereview.agent.entity.AgentModelCall;
import com.codereview.agent.entity.AgentModelConfig;
import com.codereview.agent.entity.AgentReviewTask;
import com.codereview.agent.entity.AgentRun;
import com.codereview.agent.entity.AgentToolCall;
import com.codereview.agent.invocation.InvocationRequest;
import com.codereview.agent.invocation.InvocationResult;
import com.codereview.agent.invocation.InvocationScope;
import com.codereview.agent.invocation.ModelInvocationService;
import com.codereview.agent.invocation.ModelSnapshot;
import com.codereview.agent.mapper.AgentCallMaterialMapper;
import com.codereview.agent.mapper.AgentModelConfigMapper;
import com.codereview.agent.mapper.AgentModelCallMapper;
import com.codereview.agent.mapper.AgentReviewTaskMapper;
import com.codereview.agent.mapper.AgentRunMapper;
import com.codereview.agent.mapper.AgentToolCallMapper;
import com.codereview.agent.prompt.AgentPromptInput;
import com.codereview.agent.prompt.AssembledPrompt;
import com.codereview.agent.prompt.PromptAssemblyService;
import com.codereview.agent.prompt.PromptMessage;
import com.codereview.agent.prompt.ToolCall;
import com.codereview.agent.support.AgentIds;
import com.codereview.agent.support.AgentTime;
import com.codereview.agent.tools.AgentProtocolException;
import com.codereview.agent.tools.AgentSubmission;
import com.codereview.agent.tools.AgentToolNames;
import com.codereview.agent.tools.AgentToolSchema;
import com.codereview.agent.tools.AgentToolScope;
import com.codereview.agent.tools.ReadOnlyToolService;
import com.codereview.agent.tools.ToolEnvelope;
import com.codereview.agent.tools.ToolEnvelopes;
import com.codereview.agent.tools.ToolFailure;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Agent 简单执行循环。
 *
 * <p>模型决定下一步、调用只读工具、拿到材料后继续或显式提交；程序负责版本范围、计量、
 * 状态与结果发布。首版不追问、不压缩、不恢复、不提供仓库写入或动态 Skill 加载。
 *
 * <p>硬限制在每轮与每次工具执行前检查：48 轮、96 次网络尝试、192 次工具、20 分钟任务期限、
 * 上下文 80%、连续 3 轮无进展。命中限制时保留已发生的调用与材料，不追加总结调用，
 * 也不发布完整结果。
 */
@Component
@ConditionalOnProperty(prefix = "agent.datasource", name = "url")
public class AgentTaskWorker implements TaskWorker {

    private static final Logger log = LoggerFactory.getLogger(AgentTaskWorker.class);
    private static final int MAX_TOOLS_PER_TURN = 4;
    private static final int MAX_PROTOCOL_ERRORS = 2;

    private final AgentDatabase db;
    private final ObjectMapper mapper;
    private final TaskExecutionGuard guard;
    private final PromptAssemblyService assembly;
    private final ModelInvocationService invocation;
    private final ReadOnlyToolService tools;
    private final AgentResultPublisher publisher;

    public AgentTaskWorker(AgentDatabase db, ObjectMapper mapper, TaskExecutionGuard guard,
                           PromptAssemblyService assembly, ModelInvocationService invocation,
                           ReadOnlyToolService tools, AgentResultPublisher publisher) {
        this.db = db;
        this.mapper = mapper;
        this.guard = guard;
        this.assembly = assembly;
        this.invocation = invocation;
        this.tools = tools;
        this.publisher = publisher;
    }

    @Override
    public String route() {
        return "AGENT";
    }

    @Override
    public TaskOutcome execute(AgentReviewTask task) {
        AgentExecution execution;
        try {
            execution = load(task);
        } catch (ApiException e) {
            log.warn("Agent 任务 {} 初始化失败：{}", task.getId(), e.getMessage());
            return TaskOutcome.failed(e.errorCode().code());
        } catch (RuntimeException e) {
            log.error("Agent 任务 {} 初始化异常", task.getId(), e);
            return TaskOutcome.failed(TaskStates.STOP_EXECUTION_ERROR);
        }

        AgentRun run = startRun(task);
        try {
            return loop(task, execution, run);
        } catch (ApiException e) {
            if (e.errorCode() == ErrorCode.TASK_CANCELLED) {
                return stopBecauseTaskAlreadyClosed(task, run);
            }
            if (e.errorCode() == ErrorCode.TASK_DEADLINE) {
                return limit(execution, run, TaskStates.STOP_TASK_DEADLINE);
            }
            if (e.errorCode() == ErrorCode.QUOTA_EXCEEDED) {
                return limit(execution, run, TaskStates.STOP_NETWORK_LIMIT);
            }
            if (e.errorCode() == ErrorCode.CONTEXT_LIMIT) {
                return limit(execution, run, TaskStates.STOP_CONTEXT_LIMIT);
            }
            log.warn("Agent 任务 {} 执行失败：{}", task.getId(), e.getMessage());
            finishRun(run, TaskStates.FAILED, e.errorCode().code());
            return TaskOutcome.failed(e.errorCode().code());
        } catch (RuntimeException e) {
            log.error("Agent 任务 {} 执行异常", task.getId(), e);
            finishRun(run, TaskStates.FAILED, TaskStates.STOP_EXECUTION_ERROR);
            return TaskOutcome.failed(TaskStates.STOP_EXECUTION_ERROR);
        }
    }

    private TaskOutcome loop(AgentReviewTask task, AgentExecution execution, AgentRun run) {
        while (run.getTurnNo() < AgentBudget.MODEL_TURNS) {
            guard.requireActive(task.getId());
            int turn = run.getTurnNo() + 1;
            if (run.getNetworkAttempts() + 2 > AgentBudget.NETWORK_ATTEMPTS) {
                return limit(execution, run, TaskStates.STOP_NETWORK_LIMIT);
            }
            AssembledPrompt prompt = assembly.assembleAgent(execution.input,
                    execution.history, AgentToolSchema.agentTools());
            if (!withinContext(execution.model, prompt)) {
                return limit(execution, run, TaskStates.STOP_CONTEXT_LIMIT);
            }

            InvocationResult result;
            try {
                result = invocation.invoke(new InvocationRequest(execution.model, prompt,
                        new InvocationScope(task.getId(), null, null, task.getDeadlineAt()),
                        false, null, AgentBudget.OUTPUT_TOKENS, turn, AgentBudget.MODEL_TIMEOUT_SECONDS));
            } catch (ApiException e) {
                if (e.errorCode() == ErrorCode.MODEL_OUTPUT_INVALID) {
                    execution.protocolErrors++;
                    if (execution.protocolErrors > MAX_PROTOCOL_ERRORS) {
                        return limit(execution, run, TaskStates.STOP_MODEL_OUTPUT_INVALID);
                    }
                    execution.history.add(new PromptMessage("user",
                            "上一轮输出不可用（" + e.getMessage() + "）。请重新给出工具调用或提交结果。"));
                    updateRun(run, turn, run.getToolAttempts(),
                            run.getNetworkAttempts() + 1, "PROTOCOL_RETRY");
                    continue;
                }
                throw e;
            }
            run.setTurnNo(turn);
            run.setNetworkAttempts(run.getNetworkAttempts() + Math.max(1, result.attempts()));
            markSent(execution, result.callId());

            List<ToolCall> calls = result.toolCalls();
            if (calls.isEmpty()) {
                execution.protocolErrors++;
                if (execution.protocolErrors > MAX_PROTOCOL_ERRORS) {
                    return limit(execution, run, TaskStates.STOP_PROTOCOL_INVALID);
                }
                execution.history.add(new PromptMessage("assistant", result.content()));
                execution.history.add(new PromptMessage("user", """
                        上一轮只有正文、没有工具调用。请二选一：
                        - 调用只读工具继续调查；
                        - 调用 submit_result 提交结果。
                        不要只回复计划或说明。"""));
                updateRun(run, turn, run.getToolAttempts(), run.getNetworkAttempts(),
                        "PROTOCOL_RETRY");
                continue;
            }
            boolean submit = calls.stream().anyMatch(call -> AgentToolNames.isSubmit(call.name()));
            if (calls.size() > MAX_TOOLS_PER_TURN || (submit && calls.size() > 1)) {
                execution.protocolErrors++;
                if (execution.protocolErrors > MAX_PROTOCOL_ERRORS) {
                    return limit(execution, run, TaskStates.STOP_PROTOCOL_INVALID);
                }
                execution.history.add(PromptMessage.assistantToolCalls(calls, result.content()));
                String batchError = "上一轮工具批次不合法：单轮最多 4 个工具；submit_result 必须单独调用，"
                        + "不能与其他工具同批。本批工具没有执行。";
                for (int index = 0; index < calls.size(); index++) {
                    ToolCall call = calls.get(index);
                    ToolEnvelope rejected = ToolEnvelope.failure(call.id(),
                            ToolFailure.invalid("TOOL_BATCH_INVALID", batchError), execution.scope);
                    String envelopeJson = ToolEnvelopes.writeString(mapper, rejected);
                    persistToolCall(task.getId(), result.callId(), turn, index + 1, call,
                            rejected, envelopeJson, "REJECTED_BATCH");
                    execution.history.add(PromptMessage.toolResult(call.id(), envelopeJson));
                }
                execution.history.add(new PromptMessage("user", """
                        上一轮工具批次不合法，已为其中每个调用返回拒绝结果；本批工具没有执行。
                        请减少为每轮最多 4 个工具，且 submit_result 必须单独调用，然后重新发起。"""));
                updateRun(run, turn, run.getToolAttempts(), run.getNetworkAttempts(),
                        "PROTOCOL_RETRY");
                continue;
            }
            if (submit) {
                TaskOutcome outcome = submit(task, execution, run, result,
                        calls.get(0), turn);
                if (outcome != null) {
                    return outcome;
                }
                continue;
            }

            execution.history.add(PromptMessage.assistantToolCalls(calls, result.content()));
            Set<String> before = new LinkedHashSet<>(execution.pending.keySet());
            StringBuilder signature = new StringBuilder();
            for (int index = 0; index < calls.size(); index++) {
                guard.requireActive(task.getId());
                if (run.getToolAttempts() >= AgentBudget.TOOL_ATTEMPTS) {
                    return limit(execution, run, TaskStates.STOP_TOOL_LIMIT);
                }
                run.setToolAttempts(run.getToolAttempts() + 1);
                ToolCall call = calls.get(index);
                ToolEnvelope envelope = tools.execute(call.id(), call.name(), call.arguments(),
                        execution.scope);
                String envelopeJson = ToolEnvelopes.writeString(mapper, envelope);
                persistToolCall(task.getId(), result.callId(), turn, index + 1, call, envelope,
                        envelopeJson);
                collectMaterials(execution, envelope);
                execution.toolCalls++;
                execution.history.add(PromptMessage.toolResult(call.id(), envelopeJson));
                signature.append(turnSignature(call, envelope));
            }
            boolean newMaterial = execution.pending.keySet().stream()
                    .anyMatch(id -> !before.contains(id) && !execution.sent.containsKey(id));
            boolean repeated = signature.toString().equals(execution.lastSignature) && !newMaterial;
            execution.lastSignature = signature.toString();
            if (repeated) {
                execution.repeats++;
                if (execution.repeats == 1) {
                    execution.history.add(new PromptMessage("user", """
                            上一轮工具调用与返回完全相同，且没有新增材料。请换查询、翻页或结束调查；
                            重复同一请求不会得到更多信息。"""));
                }
                if (execution.repeats >= AgentBudget.NO_PROGRESS_TURNS - 1) {
                    return limit(execution, run, TaskStates.STOP_NO_PROGRESS_LIMIT);
                }
            } else {
                execution.repeats = 0;
            }
            updateRun(run, turn, run.getToolAttempts(), run.getNetworkAttempts(), "TOOL_CALL");
        }
        return limit(execution, run, TaskStates.STOP_TURN_LIMIT);
    }

    /**
     * 提交结果；返回 null 表示协议错误已记入历史、应继续下一轮。
     */
    private TaskOutcome submit(AgentReviewTask task, AgentExecution execution, AgentRun run,
                               InvocationResult result, ToolCall call, int turn) {
        AgentSubmission submission;
        try {
            submission = AgentSubmission.parse(mapper, call.arguments());
            submission = AgentSubmission.validate(submission, execution.sent);
        } catch (AgentProtocolException e) {
            persistSubmitCall(task.getId(), result.callId(), turn, call,
                    ToolEnvelope.INVALID_ARGUMENT, e.getMessage());
            execution.protocolErrors++;
            if (execution.protocolErrors > MAX_PROTOCOL_ERRORS) {
                return limit(execution, run, TaskStates.STOP_PROTOCOL_INVALID);
            }
            execution.history.add(PromptMessage.assistantToolCalls(List.of(call), result.content()));
            execution.history.add(PromptMessage.toolResult(call.id(), errorEnvelope(
                    "submit_result 未通过校验：" + e.getMessage())));
            updateRun(run, turn, run.getToolAttempts(), run.getNetworkAttempts(), "PROTOCOL_RETRY");
            return null;
        }
        persistSubmitCall(task.getId(), result.callId(), turn, call,
                ToolEnvelope.OK, "提交结果：" + submission.answerStatus());
        String resultId = publisher.publish(task.getId(), submission,
                List.copyOf(execution.sent.values()), execution.comparisons, execution.toolCalls);
        finishRun(run, TaskStates.SUCCEEDED,
                submission.answered() ? null : TaskStates.STOP_INSUFFICIENT_EVIDENCE);
        log.info("Agent 任务 {} 发布结果 {}（{}）", task.getId(), resultId, submission.answerStatus());
        return new TaskOutcome(TaskStates.SUCCEEDED,
                submission.answered() ? null : TaskStates.STOP_INSUFFICIENT_EVIDENCE);
    }

    /**
     * 守卫把“用户取消”和“任务已被其他路径结束”都报成 {@code TASK_CANCELLED}。
     * 期限扫描或重启收敛已经写下的终态必须原样记到进度上，不能改成用户取消。
     */
    private TaskOutcome stopBecauseTaskAlreadyClosed(AgentReviewTask task, AgentRun run) {
        AgentReviewTask current = db.mapper(AgentReviewTaskMapper.class).selectById(task.getId());
        if (current != null && TaskStates.isTerminal(current.getState())
                && !TaskStates.CANCELLED.equals(current.getState())) {
            finishRun(run, current.getState(), current.getStopReason());
            return new TaskOutcome(current.getState(), current.getStopReason());
        }
        finishRun(run, TaskStates.CANCELLED, TaskStates.STOP_USER_CANCELLED);
        return new TaskOutcome(TaskStates.CANCELLED, TaskStates.STOP_USER_CANCELLED);
    }

    /** 限制类终止：有调查记录为 PARTIAL，没有任何工具/模型记录为 FAILED。 */
    private TaskOutcome limit(AgentExecution execution, AgentRun run, String reason) {
        boolean investigated = run.getToolAttempts() > 0 || execution.toolCalls > 0;
        String state = investigated ? TaskStates.PARTIAL : TaskStates.FAILED;
        finishRun(run, state, reason);
        return investigated ? TaskOutcome.partial(reason) : TaskOutcome.failed(reason);
    }

    private boolean withinContext(ModelSnapshot model, AssembledPrompt prompt) {
        Integer window = model.contextWindow();
        if (window == null || window <= 0) {
            return false;
        }
        int estimate = prompt.inputEstimate();
        int safety = Math.max(1024, window / 10);
        return estimate <= window * AgentBudget.CONTEXT_STOP_RATIO
                && (long) estimate + AgentBudget.OUTPUT_TOKENS + safety <= window;
    }

    private void markSent(AgentExecution execution, String callId) {
        if (execution.pending.isEmpty()) {
            return;
        }
        for (AgentSubmission.SentMaterial material : execution.pending.values()) {
            if (execution.sent.containsKey(material.materialId())) {
                continue;
            }
            try {
                AgentCallMaterial row = new AgentCallMaterial();
                row.setCallId(callId);
                row.setMaterialId(material.materialId());
                row.setRole("TOOL");
                row.setDisposition("SENT");
                db.mapper(AgentCallMaterialMapper.class).insert(row);
            } catch (DuplicateKeyException duplicate) {
                // 同一调用已经记下这份材料时不阻断；其他写入失败必须中止，
                // 否则未落库的材料仍会被当成“已送入模型”而允许引用。
            }
            execution.sent.put(material.materialId(), material);
        }
        execution.pending.clear();
    }

    private void collectMaterials(AgentExecution execution, ToolEnvelope envelope) {
        for (ToolEnvelope.ToolMaterial material : envelope.materials()) {
            execution.pending.putIfAbsent(material.materialId(),
                    new AgentSubmission.SentMaterial(material.materialId(), material.path(),
                            material.side(), material.sha(), material.startLine(),
                            material.endLine(), material.contentHash()));
        }
    }

    private void persistSubmitCall(String taskId, String callId, int turn, ToolCall call,
                                   String status, String diagnostic) {
        ToolEnvelope envelope = new ToolEnvelope(call.id(), status, List.of(), null,
                List.of(), null, ToolEnvelope.UNKNOWN, null, diagnostic);
        persistToolCall(taskId, callId, turn, 1, call, envelope,
                ToolEnvelopes.writeString(mapper, envelope));
    }

    private void persistToolCall(String taskId, String callId, int turn, int ordinal,
                                 ToolCall call, ToolEnvelope envelope, String envelopeJson) {
        persistToolCall(taskId, callId, turn, ordinal, call, envelope, envelopeJson,
                envelope.status());
    }

    private void persistToolCall(String taskId, String callId, int turn, int ordinal,
                                 ToolCall call, ToolEnvelope envelope, String envelopeJson,
                                 String recordStatus) {
        LocalDateTime now = AgentTime.now();
        AgentToolCall row = new AgentToolCall();
        row.setId(AgentIds.next());
        row.setTaskId(taskId);
        row.setModelCallId(callId);
        row.setTurnNo(turn);
        row.setOrdinal(ordinal);
        row.setProviderToolCallId(call.id());
        row.setToolName(call.name());
        row.setArguments(call.arguments());
        row.setEnvelope(envelopeJson);
        row.setMaterialIds(toJson(envelope.materials().stream()
                .map(ToolEnvelope.ToolMaterial::materialId).toList()));
        row.setStatus(recordStatus);
        row.setStartedAt(now);
        row.setFinishedAt(now);
        db.mapper(AgentToolCallMapper.class).insert(row);
    }

    private String turnSignature(ToolCall call, ToolEnvelope envelope) {
        List<String> materialIds = envelope.materials().stream()
                .map(ToolEnvelope.ToolMaterial::materialId).sorted().toList();
        return call.name() + "|" + call.arguments() + "|" + envelope.status() + "|" + materialIds + "\n";
    }

    private String errorEnvelope(String diagnostic) {
        ObjectNode node = mapper.createObjectNode();
        node.put("status", ToolEnvelope.INVALID_ARGUMENT);
        node.put("diagnostic", diagnostic);
        return toJson(node);
    }

    private AgentRun startRun(AgentReviewTask task) {
        AgentRun existing = db.mapper(AgentRunMapper.class).selectById(task.getId());
        LocalDateTime now = AgentTime.now();
        if (existing != null) {
            existing.setState(TaskStates.RUNNING);
            existing.setUpdatedAt(now);
            db.mapper(AgentRunMapper.class).updateById(existing);
            return existing;
        }
        AgentRun run = new AgentRun();
        run.setTaskId(task.getId());
        run.setTurnNo(0);
        run.setToolAttempts(0);
        run.setNetworkAttempts(0);
        run.setState(TaskStates.RUNNING);
        run.setRevision(0);
        run.setCreatedAt(now);
        run.setUpdatedAt(now);
        db.mapper(AgentRunMapper.class).insert(run);
        return run;
    }

    private void updateRun(AgentRun run, int turn, int toolAttempts, int networkAttempts,
                           String action) {
        run.setTurnNo(turn);
        run.setToolAttempts(toolAttempts);
        run.setNetworkAttempts(networkAttempts);
        run.setCurrentAction(action);
        run.setCoveredJson(fileCoverage(run));
        run.setUpdatedAt(AgentTime.now());
        db.mapper(AgentRunMapper.class).updateById(run);
    }

    private void finishRun(AgentRun run, String state, String stopReason) {
        run.setState(state);
        run.setStopReason(stopReason);
        run.setCurrentAction(state);
        run.setCoveredJson(fileCoverage(run));
        run.setUpdatedAt(AgentTime.now());
        // 结束轮次的计数只写在内存里，updateRun 还没来得及落库；这里必须一并写入，
        // 否则进度接口会少记最后一轮（例如直接提交，或协议错误耗尽时的最后一次调用）。
        db.mapper(AgentRunMapper.class).update(null,
                new LambdaUpdateWrapper<AgentRun>()
                        .eq(AgentRun::getTaskId, run.getTaskId())
                        .set(AgentRun::getTurnNo, run.getTurnNo())
                        .set(AgentRun::getToolAttempts, run.getToolAttempts())
                        .set(AgentRun::getNetworkAttempts, run.getNetworkAttempts())
                        .set(AgentRun::getCurrentAction, state)
                        .set(AgentRun::getCoveredJson, run.getCoveredJson())
                        .set(AgentRun::getState, state)
                        .set(AgentRun::getStopReason, stopReason)
                        .set(AgentRun::getUpdatedAt, run.getUpdatedAt())
                        .setSql("revision = revision + 1"));
    }

    private String fileCoverage(AgentRun run) {
        ObjectNode node = mapper.createObjectNode();
        node.put("turnNo", run.getTurnNo());
        node.put("toolAttempts", run.getToolAttempts());
        node.put("networkAttempts", run.getNetworkAttempts());
        return toJson(node);
    }

    private AgentExecution load(AgentReviewTask task) {
        JsonNode input = readJson(task.getInputSnapshot());
        JsonNode config = readJson(task.getConfigSnapshot());
        String modelId = config.path("model").path("id").asText(null);
        Integer revision = config.path("model").path("revision").isInt()
                ? config.path("model").path("revision").asInt() : null;
        AgentModelConfig model = modelId == null ? null
                : db.mapper(AgentModelConfigMapper.class).selectById(modelId);
        if (model == null) {
            throw new ApiException(ErrorCode.CONFIG_INCOMPLETE, "Agent 任务的模型配置不存在");
        }
        if (revision != null && !revision.equals(model.getRevision())) {
            throw new ApiException(ErrorCode.CONFIG_INCOMPLETE,
                    "模型配置修订已变化，停止使用旧快照调用");
        }
        Map<String, String> versions = new LinkedHashMap<>();
        for (JsonNode version : input.path("scope").path("versions")) {
            versions.put(version.path("version").asText(), version.path("sha").asText());
        }
        Map<String, AgentToolScope.Comparison> comparisons = new LinkedHashMap<>();
        List<Map<String, Object>> comparisonList = new ArrayList<>();
        for (JsonNode comparison : input.path("scope").path("comparisons")) {
            String id = comparison.path("id").asText();
            String head = comparison.path("headSha").asText();
            String base = comparison.path("baseSha").isNull()
                    ? null : comparison.path("baseSha").asText();
            comparisons.put(id, new AgentToolScope.Comparison(id, head, base));
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", id);
            entry.put("headSha", head);
            entry.put("baseSha", base);
            comparisonList.add(entry);
        }
        List<AgentPromptInput.Version> promptVersions = new ArrayList<>();
        versions.forEach((ref, sha) -> promptVersions.add(new AgentPromptInput.Version(ref, sha)));
        List<AgentPromptInput.Comparison> promptComparisons = new ArrayList<>();
        for (Map<String, Object> entry : comparisonList) {
            promptComparisons.add(new AgentPromptInput.Comparison(
                    String.valueOf(entry.get("id")), String.valueOf(entry.get("headSha")),
                    (String) entry.get("baseSha")));
        }
        JsonNode scopeNode = input.path("scope");
        AgentPromptInput promptInput = new AgentPromptInput(
                input.path("requirements").asText(""), scopeNode.path("kind").asText("TEXT"),
                scopeNode.path("branch").isNull() ? null : scopeNode.path("branch").asText(),
                scopeNode.path("headSha").isNull() ? null : scopeNode.path("headSha").asText(),
                scopeNode.path("description").isNull() ? null : scopeNode.path("description").asText(),
                promptVersions, promptComparisons,
                config.path("prompt").path("content").isMissingNode() ? null
                        : config.path("prompt").path("content").asText(),
                input.path("reference").path("body").isMissingNode() ? null
                        : input.path("reference").path("body").asText());
        return new AgentExecution(task, promptInput,
                new AgentToolScope(task.getProjectId(), versions, comparisons),
                ModelSnapshot.from(model), comparisonList);
    }

    private JsonNode readJson(String value) {
        try {
            return value == null || value.isBlank() ? mapper.createObjectNode()
                    : mapper.readTree(value);
        } catch (Exception e) {
            return mapper.createObjectNode();
        }
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Agent 快照序列化失败", e);
        }
    }

    /** 一轮执行的可变状态；只存在于本次执行内，重启后不恢复原会话。 */
    private static final class AgentExecution {

        private final AgentReviewTask task;
        private final AgentPromptInput input;
        private final AgentToolScope scope;
        private final ModelSnapshot model;
        private final List<Map<String, Object>> comparisons;
        private final List<PromptMessage> history = new ArrayList<>();
        private final Map<String, AgentSubmission.SentMaterial> pending = new LinkedHashMap<>();
        private final Map<String, AgentSubmission.SentMaterial> sent = new LinkedHashMap<>();
        private int protocolErrors;
        private int repeats;
        private int toolCalls;
        private String lastSignature;

        private AgentExecution(AgentReviewTask task, AgentPromptInput input, AgentToolScope scope,
                               ModelSnapshot model, List<Map<String, Object>> comparisons) {
            this.task = task;
            this.input = input;
            this.scope = scope;
            this.model = model;
            this.comparisons = comparisons;
        }
    }
}
