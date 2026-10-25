package com.codereview.agent.execution;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.codereview.agent.api.ApiException;
import com.codereview.agent.api.ErrorCode;
import com.codereview.agent.db.AgentDatabase;
import com.codereview.agent.entity.AgentCallMaterial;
import com.codereview.agent.entity.AgentExecutionUnit;
import com.codereview.agent.entity.AgentModelCall;
import com.codereview.agent.entity.AgentModelConfig;
import com.codereview.agent.entity.AgentReviewTask;
import com.codereview.agent.entity.AgentTaskTarget;
import com.codereview.agent.invocation.InvocationRequest;
import com.codereview.agent.invocation.InvocationResult;
import com.codereview.agent.invocation.InvocationScope;
import com.codereview.agent.invocation.ModelCapacityService;
import com.codereview.agent.invocation.ModelInvocationService;
import com.codereview.agent.invocation.ModelSnapshot;
import com.codereview.agent.mapper.AgentCallMaterialMapper;
import com.codereview.agent.mapper.AgentExecutionUnitMapper;
import com.codereview.agent.mapper.AgentModelCallMapper;
import com.codereview.agent.mapper.AgentModelConfigMapper;
import com.codereview.agent.mapper.AgentReviewTaskMapper;
import com.codereview.agent.mapper.AgentTaskTargetMapper;
import com.codereview.agent.prompt.AssembledPrompt;
import com.codereview.agent.prompt.PromptAssemblyService;
import com.codereview.agent.prompt.PromptInput;
import com.codereview.agent.repository.RepositoryReadService;
import com.codereview.agent.specialist.capacity.SpecialistSplitPlanner;
import com.codereview.agent.specialist.capacity.SpecialistUnitPlan;
import com.codereview.agent.specialist.coupling.CouplingFacts;
import com.codereview.agent.specialist.coupling.CouplingFactsBuilder;
import com.codereview.agent.specialist.material.DeclarationEnhancementService;
import com.codereview.agent.specialist.material.DeclarationResolver;
import com.codereview.agent.specialist.material.JavaStructureParser;
import com.codereview.agent.specialist.material.MaterialPreparationService;
import com.codereview.agent.specialist.material.SpecialistMaterials;
import com.codereview.agent.specialist.protocol.SpecialistPromptBuilder;
import com.codereview.agent.specialist.protocol.SpecialistProtocolPolicy;
import com.codereview.agent.specialist.protocol.SpecialistResultParser;
import com.codereview.agent.specialist.protocol.SpecialistResultValidator;
import com.codereview.agent.specialist.protocol.SpecialistResults;
import com.codereview.agent.support.AgentIds;
import com.codereview.agent.support.AgentTime;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.codereview.agent.specialist.material.SpecialistMaterials.Material;
import static com.codereview.agent.specialist.material.SpecialistMaterials.PrepareCommand;
import static com.codereview.agent.specialist.material.SpecialistMaterials.PreparedTarget;
import static com.codereview.agent.specialist.material.SpecialistMaterials.Side;

/**
 * 三类专项的逐文件执行器。
 *
 * <p>每个目标独立准备、规划、调用和发布；一个文件失败不丢弃其他文件的结果。
 * 片段与文件共用公共模型并发许可；每次实际调用先由公共入口落盘，格式错误最多再试 2 次。
 * 完整发布要求全部单元成功；部分成功发布 INCOMPLETE 结果，供阅读但不能进入正式选材。
 */
@Service
@ConditionalOnProperty(prefix = "agent.datasource", name = "url")
public class SpecialistTaskWorker implements TaskWorker {

    private static final Logger log = LoggerFactory.getLogger(SpecialistTaskWorker.class);

    private final AgentDatabase db;
    private final ObjectMapper mapper;
    private final MaterialPreparationService preparation;
    private final DeclarationEnhancementService enhancement;
    private final CouplingFactsBuilder couplingFacts;
    private final RepositoryReadService repository;
    private final JavaStructureParser structureParser;
    private final SpecialistSplitPlanner planner;
    private final PromptAssemblyService promptAssembly;
    private final SpecialistPromptBuilder promptBuilder;
    private final SpecialistResultParser resultParser;
    private final SpecialistResultValidator validator;
    private final SpecialistResultPublisher publisher;
    private final ModelInvocationService invocation;
    private final ModelCapacityService capacity;
    private final TaskExecutionGuard guard;
    private final SpecialistExecutionProperties properties;

    public SpecialistTaskWorker(AgentDatabase db, ObjectMapper mapper,
                                MaterialPreparationService preparation,
                                DeclarationEnhancementService enhancement,
                                CouplingFactsBuilder couplingFacts,
                                RepositoryReadService repository,
                                JavaStructureParser structureParser,
                                SpecialistSplitPlanner planner,
                                PromptAssemblyService promptAssembly,
                                SpecialistPromptBuilder promptBuilder,
                                SpecialistResultParser resultParser,
                                SpecialistResultValidator validator,
                                SpecialistResultPublisher publisher,
                                ModelInvocationService invocation,
                                ModelCapacityService capacity,
                                TaskExecutionGuard guard,
                                SpecialistExecutionProperties properties) {
        this.db = db;
        this.mapper = mapper;
        this.preparation = preparation;
        this.enhancement = enhancement;
        this.couplingFacts = couplingFacts;
        this.repository = repository;
        this.structureParser = structureParser;
        this.planner = planner;
        this.promptAssembly = promptAssembly;
        this.promptBuilder = promptBuilder;
        this.resultParser = resultParser;
        this.validator = validator;
        this.publisher = publisher;
        this.invocation = invocation;
        this.capacity = capacity;
        this.guard = guard;
        this.properties = properties;
    }

    @Override
    public TaskOutcome execute(AgentReviewTask task) {
        List<AgentTaskTarget> targets = db.mapper(AgentTaskTargetMapper.class).selectList(
                new LambdaQueryWrapper<AgentTaskTarget>()
                        .eq(AgentTaskTarget::getTaskId, task.getId())
                        .orderByAsc(AgentTaskTarget::getOrdinal));
        if (targets.isEmpty()) {
            return TaskOutcome.failed("NO_TARGETS");
        }
        int threads = Math.max(1, Math.min(properties.getParallelTargets(), targets.size()));
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<SpecialistTargetOutcome>> futures = new ArrayList<>();
        try {
            for (AgentTaskTarget target : targets) {
                futures.add(pool.submit(() -> executeTarget(task, target)));
            }
            List<SpecialistTargetOutcome> outcomes = new ArrayList<>();
            for (int i = 0; i < futures.size(); i++) {
                try {
                    outcomes.add(futures.get(i).get());
                } catch (ExecutionException failure) {
                    Throwable cause = failure.getCause();
                    if (cause instanceof ApiException api
                            && (api.errorCode() == ErrorCode.TASK_CANCELLED
                            || api.errorCode() == ErrorCode.TASK_DEADLINE)) {
                        futures.forEach(future -> future.cancel(true));
                        throw api;
                    }
                    log.error("目标 {} 执行异常", targets.get(i).getId(), cause);
                    finishTarget(targets.get(i).getId(), TaskStates.FAILED, "EXECUTION_ERROR");
                    outcomes.add(new SpecialistTargetOutcome(targets.get(i).getId(),
                            TaskStates.FAILED, "EXECUTION_ERROR"));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new ApiException(ErrorCode.TASK_CANCELLED, "任务执行被中断");
                }
            }
            long succeeded = outcomes.stream()
                    .filter(outcome -> TaskStates.SUCCEEDED.equals(outcome.state())).count();
            long usable = outcomes.stream()
                    .filter(outcome -> TaskStates.SUCCEEDED.equals(outcome.state())
                            || TaskStates.PARTIAL.equals(outcome.state())).count();
            if (succeeded == outcomes.size()) {
                return TaskOutcome.succeeded();
            }
            if (usable > 0) {
                return TaskOutcome.partial("SOME_TARGETS_FAILED");
            }
            return TaskOutcome.failed("ALL_TARGETS_FAILED");
        } finally {
            pool.shutdownNow();
        }
    }

    private SpecialistTargetOutcome executeTarget(AgentReviewTask task, AgentTaskTarget target) {
        try {
            guard.requireActive(task.getId());
            updateTarget(target.getId(), TaskStates.PREPARING, null);
            PreparedTarget prepared = preparation.prepare(command(task, target));
            if (prepared.failed()) {
                finishTarget(target.getId(), TaskStates.FAILED, prepared.errorCode());
                return new SpecialistTargetOutcome(target.getId(), TaskStates.FAILED,
                        prepared.errorCode());
            }
            PreparedTarget enhanced = enhancement.enhance(prepared);
            ModelSnapshot model = model(task);
            GeneralRule generalRule = generalRule(task);
            CouplingFacts facts = facts(task, enhanced);
            boolean smartSplit = read(task.getInputSnapshot()).path("smartSplit").asBoolean(false);
            ModelSnapshot planningModel = model;
            for (int generation = 1; generation <= 3; generation++) {
                List<SpecialistUnitPlan> units = planner.plan(enhanced, facts, generalRule.name(),
                        generalRule.content(), planningModel, smartSplit);
                updateTarget(target.getId(), TaskStates.RUNNING, null);
                List<SpecialistUnitExecution> executions = new ArrayList<>();
                boolean unitFailed = false;
                String lastUnitError = null;
                boolean replan = false;
                for (SpecialistUnitPlan unit : units) {
                    guard.requireActive(task.getId());
                    AgentExecutionUnit row = createUnit(target.getId(), unit, generation);
                    try {
                        SpecialistUnitExecution execution = executeUnit(task, target, unit, row,
                                model, facts, generalRule, !smartSplit);
                        guard.requireActive(task.getId());
                        updateUnit(row.getId(), TaskStates.SUCCEEDED);
                        executions.add(execution);
                    } catch (ApiException failure) {
                        updateUnit(row.getId(), TaskStates.FAILED);
                        if (failure.errorCode() == ErrorCode.TASK_CANCELLED
                                || failure.errorCode() == ErrorCode.TASK_DEADLINE) {
                            throw failure;
                        }
                        if (failure.errorCode() == ErrorCode.CONTEXT_LIMIT && smartSplit) {
                            if (generation == 3) {
                                throw failure;
                            }
                            var estimate = capacity.estimate(promptAssembly.assemble(
                                    promptBuilder.build(unit.target(), facts, generalRule.name(),
                                            generalRule.content())), planningModel);
                            int window = Math.max(1, Math.min(planningModel.contextWindow() - 1,
                                    (int) (estimate.required() * 0.75)));
                            planningModel = new ModelSnapshot(model.id(), model.name(), model.baseUrl(),
                                    model.modelName(), model.credential(), window, model.maxOutput(),
                                    model.tokenizer(), model.protocol(), model.revision());
                            replan = true;
                            break;
                        }
                        unitFailed = true;
                        lastUnitError = failure.errorCode().code();
                    }
                }
                if (replan) {
                    // 旧代仅保留诊断，不能带入新代结果。
                    continue;
                }
                if (executions.isEmpty()) {
                    finishTarget(target.getId(), TaskStates.FAILED, lastUnitError);
                    return new SpecialistTargetOutcome(target.getId(), TaskStates.FAILED, lastUnitError);
                }
                boolean complete = !unitFailed;
                String state = complete ? TaskStates.SUCCEEDED : TaskStates.PARTIAL;
                db.inTransaction(() -> {
                    guard.requireActiveLocked(task.getId());
                    publisher.publish(task, target, enhanced, executions, complete);
                    if (finishTarget(target.getId(), state, complete ? null : "PARTIAL_UNITS") == 0) {
                        throw new ApiException(ErrorCode.TASK_CANCELLED, "目标已结束，拒绝迟到发布");
                    }
                    return null;
                });
                return new SpecialistTargetOutcome(target.getId(), state, null);
            }
            throw new ApiException(ErrorCode.CONTEXT_LIMIT, "容量重规划次数已用尽");
        } catch (ApiException failure) {
            if (failure.errorCode() == ErrorCode.TASK_CANCELLED
                    || failure.errorCode() == ErrorCode.TASK_DEADLINE) {
                finishTarget(target.getId(), TaskStates.CANCELLED, failure.errorCode().code());
                throw failure;
            }
            finishTarget(target.getId(), TaskStates.FAILED, failure.errorCode().code());
            return new SpecialistTargetOutcome(target.getId(), TaskStates.FAILED,
                    failure.errorCode().code());
        }
    }

    private SpecialistUnitExecution executeUnit(AgentReviewTask task, AgentTaskTarget target,
                                                SpecialistUnitPlan unit, AgentExecutionUnit row,
                                                ModelSnapshot model, CouplingFacts facts,
                                                GeneralRule generalRule, boolean sendWhole) {
        PromptInput.FormatRetry retry = null;
        try {
            for (int attempt = 1; attempt <= SpecialistProtocolPolicy.maxAttempts(); attempt++) {
                guard.requireActive(task.getId());
                PromptInput input = promptBuilder.build(unit.target(), facts, generalRule.name(),
                        generalRule.content(), retry);
                AssembledPrompt prompt = promptAssembly.assemble(input);
                if (!sendWhole) {
                    capacity.requireFits(prompt, model);
                }
                InvocationResult result = null;
                try {
                    result = invocation.invoke(new InvocationRequest(model, prompt,
                            new InvocationScope(task.getId(), target.getId(), row.getId(),
                                    task.getDeadlineAt()), sendWhole));
                    guard.requireActive(task.getId());
                    SpecialistResults.Result parsed = resultParser.parse(result.content(),
                            task.getSpecialistType());
                    SpecialistResultValidator.ValidatedResult validated = validator.validate(parsed,
                            promptBuilder.context(unit.target(), facts));
                    return new SpecialistUnitExecution(row.getId(), unit, validated, result.callId(), facts);
                } catch (ApiException failure) {
                    if (result != null && failure.errorCode() == ErrorCode.MODEL_OUTPUT_INVALID) {
                        db.mapper(AgentModelCallMapper.class).update(null,
                                new LambdaUpdateWrapper<AgentModelCall>()
                                        .eq(AgentModelCall::getId, result.callId())
                                        .set(AgentModelCall::getState, "FAILED")
                                        .set(AgentModelCall::getErrorCode, failure.errorCode().code())
                                        .set(AgentModelCall::getErrorMessage, failure.getMessage()));
                    }
                    if (failure.errorCode() == ErrorCode.MODEL_OUTPUT_INVALID
                            && SpecialistProtocolPolicy.shouldRetry(attempt)) {
                        retry = SpecialistProtocolPolicy.diagnostic(attempt, failure,
                                result == null ? null : result.content());
                        continue;
                    }
                    throw failure;
                }
            }
            throw new ApiException(ErrorCode.MODEL_OUTPUT_INVALID, "格式重试次数已用尽");
        } finally {
            linkMaterials(row.getId(), unit.target());
        }
    }

    private PrepareCommand command(AgentReviewTask task, AgentTaskTarget target) {
        JsonNode input = read(task.getInputSnapshot());
        return new PrepareCommand(task.getProjectId(), task.getSpecialistType(),
                input.path("source").path("kind").asText("FILES"),
                input.path("source").path("branch").asText(null),
                target.getPath(), target.getOldPath(), target.getNote(),
                target.getHeadSha(), target.getBaseSha());
    }

    private CouplingFacts facts(AgentReviewTask task, PreparedTarget target) {
        if (!"COUPLING".equals(task.getSpecialistType())) {
            return null;
        }
        Material primary = target.targets().stream()
                .filter(material -> material.side() == Side.AFTER)
                .findFirst()
                .or(() -> target.targets().stream().filter(material -> material.side() == Side.FILE)
                        .findFirst())
                .orElse(target.targets().get(0));
        DeclarationResolver resolver = new DeclarationResolver(task.getProjectId(),
                primary.sha(), repository, structureParser);
        return couplingFacts.build(target, resolver);
    }

    private ModelSnapshot model(AgentReviewTask task) {
        JsonNode config = read(task.getConfigSnapshot()).path("model");
        String id = config.path("id").asText(null);
        if (id == null || id.isBlank()) {
            throw new ApiException(ErrorCode.CONFIG_INCOMPLETE, "任务缺少模型配置快照");
        }
        AgentModelConfig model = db.mapper(AgentModelConfigMapper.class).selectById(id);
        if (model == null) {
            throw new ApiException(ErrorCode.CONFIG_INCOMPLETE, "任务使用的模型配置已不存在");
        }
        return new ModelSnapshot(id, text(config, "name", model.getName()),
                text(config, "baseUrl", model.getBaseUrl()),
                text(config, "modelName", model.getModelName()), model.getCredential(),
                integer(config, "contextWindow", model.getContextWindow()),
                integer(config, "maxOutput", model.getMaxOutput()),
                text(config, "tokenizer", model.getTokenizer()),
                text(config, "protocol", model.getProtocol() == null ? "OPENAI_CHAT" : model.getProtocol()),
                integer(config, "revision", model.getRevision()));
    }

    private GeneralRule generalRule(AgentReviewTask task) {
        JsonNode config = read(task.getConfigSnapshot());
        JsonNode prompt = config.path("prompt");
        String name = prompt.path("name").asText(null);
        String content = prompt.path("content").asText(null);
        return new GeneralRule(name, content);
    }

    private AgentExecutionUnit createUnit(String targetId, SpecialistUnitPlan plan, int generation) {
        LocalDateTime now = AgentTime.now();
        AgentExecutionUnit row = new AgentExecutionUnit();
        row.setId(AgentIds.next());
        row.setTargetId(targetId);
        row.setGeneration(generation);
        row.setOrdinal(plan.ordinal());
        row.setRanges(json(ranges(plan)));
        row.setState(TaskStates.RUNNING);
        row.setRevision(0);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        db.mapper(AgentExecutionUnitMapper.class).insert(row);
        return row;
    }

    private void linkMaterials(String unitId, PreparedTarget target) {
        List<AgentModelCall> calls = db.mapper(AgentModelCallMapper.class).selectList(
                new LambdaQueryWrapper<AgentModelCall>()
                        .eq(AgentModelCall::getUnitId, unitId)
                        .orderByAsc(AgentModelCall::getCreatedAt));
        if (calls.isEmpty()) {
            return;
        }
        for (AgentModelCall call : calls) {
            for (Material material : target.allMaterials().stream().collect(
                    java.util.stream.Collectors.toMap(Material::id, value -> value, (left, right) -> left))
                    .values()) {
                AgentCallMaterial link = new AgentCallMaterial();
                link.setCallId(call.getId());
                link.setMaterialId(material.id());
                link.setRole(material.target() ? "TARGET" : "AUXILIARY");
                link.setDisposition("SENT");
                db.mapper(AgentCallMaterialMapper.class).insert(link);
            }
        }
    }

    private void updateTarget(String targetId, String state, String errorCode) {
        db.mapper(AgentTaskTargetMapper.class).update(null,
                new LambdaUpdateWrapper<AgentTaskTarget>()
                        .eq(AgentTaskTarget::getId, targetId)
                        .notIn(AgentTaskTarget::getState, TaskStates.TARGET_TERMINAL)
                        .set(AgentTaskTarget::getState, state)
                        .set(AgentTaskTarget::getErrorCode, errorCode)
                        .set(AgentTaskTarget::getUpdatedAt, AgentTime.now())
                        .setSql("revision = revision + 1"));
    }

    private int finishTarget(String targetId, String state, String errorCode) {
        return db.mapper(AgentTaskTargetMapper.class).update(null,
                new LambdaUpdateWrapper<AgentTaskTarget>()
                        .eq(AgentTaskTarget::getId, targetId)
                        .notIn(AgentTaskTarget::getState, TaskStates.TARGET_TERMINAL)
                        .set(AgentTaskTarget::getState, state)
                        .set(AgentTaskTarget::getErrorCode, errorCode)
                        .set(AgentTaskTarget::getUpdatedAt, AgentTime.now())
                        .setSql("revision = revision + 1"));
    }

    private void updateUnit(String unitId, String state) {
        db.mapper(AgentExecutionUnitMapper.class).update(null,
                new LambdaUpdateWrapper<AgentExecutionUnit>()
                        .eq(AgentExecutionUnit::getId, unitId)
                        .set(AgentExecutionUnit::getState, state)
                        .set(AgentExecutionUnit::getUpdatedAt, AgentTime.now())
                        .setSql("revision = revision + 1"));
    }

    private List<Map<String, Integer>> ranges(SpecialistUnitPlan plan) {
        return plan.ranges().stream()
                .map(range -> Map.of("startLine", range.startLine(), "endLine", range.endLine()))
                .toList();
    }

    private JsonNode read(String value) {
        if (value == null || value.isBlank()) {
            return mapper.createObjectNode();
        }
        try {
            return mapper.readTree(value);
        } catch (JsonProcessingException e) {
            return mapper.createObjectNode();
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "单元范围序列化失败");
        }
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.path(field);
        return value.isNull() ? null
                : value.isTextual() && !value.asText().isBlank() ? value.asText() : fallback;
    }

    private static Integer integer(JsonNode node, String field, Integer fallback) {
        JsonNode value = node.path(field);
        return value.isNull() ? null : value.isNumber() ? value.asInt() : fallback;
    }

    private record GeneralRule(String name, String content) {
    }
}
