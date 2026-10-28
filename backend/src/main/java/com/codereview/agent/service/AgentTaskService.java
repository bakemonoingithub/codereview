package com.codereview.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.codereview.agent.api.ApiException;
import com.codereview.agent.api.ErrorCode;
import com.codereview.agent.db.AgentDatabase;
import com.codereview.agent.dto.AgentTaskDtos;
import com.codereview.agent.entity.AgentModelConfig;
import com.codereview.agent.entity.AgentModelValidation;
import com.codereview.agent.entity.AgentProject;
import com.codereview.agent.entity.AgentPrompt;
import com.codereview.agent.entity.AgentPromptVersion;
import com.codereview.agent.entity.AgentReviewResult;
import com.codereview.agent.entity.AgentReviewTask;
import com.codereview.agent.entity.AgentTaskQuota;
import com.codereview.agent.execution.AgentBudget;
import com.codereview.agent.execution.TaskDispatchService;
import com.codereview.agent.execution.TaskStates;
import com.codereview.agent.invocation.ModelInvocationProperties;
import com.codereview.agent.invocation.ModelSnapshot;
import com.codereview.agent.mapper.AgentModelConfigMapper;
import com.codereview.agent.mapper.AgentModelValidationMapper;
import com.codereview.agent.mapper.AgentProjectMapper;
import com.codereview.agent.mapper.AgentPromptMapper;
import com.codereview.agent.mapper.AgentReviewResultMapper;
import com.codereview.agent.mapper.AgentReviewTaskMapper;
import com.codereview.agent.mapper.AgentTaskQuotaMapper;
import com.codereview.agent.prompt.PromptAssemblyService;
import com.codereview.agent.repository.RepositoryPaths;
import com.codereview.agent.repository.RepositoryReadService;
import com.codereview.agent.repository.RepositorySnapshots;
import com.codereview.agent.support.AgentIds;
import com.codereview.agent.support.AgentTime;
import com.codereview.agent.tools.AgentToolLoopValidator;
import com.codereview.agent.tools.AgentToolNames;
import com.codereview.agent.tools.AgentToolSchema;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Agent 任务发起：校验模型准入、范围与版本、指导提示词和参考结果，冻结输入 v2 与配置 v3 快照。
 *
 * <p>文件与文字任务重新核对分支 HEAD，移动即 {@code SOURCE_MOVED}，不自动改用新版本；
 * 多提交逐项保存「提交 + 第一父提交」比较关系，合并提交取第一父提交，根提交的 base 为空。
 * 这里只创建任务；执行循环、预算消耗与结果发布由 P33.4/P33.5 接入。
 */
@Service
@ConditionalOnProperty(prefix = "agent.datasource", name = "url")
public class AgentTaskService {

    private static final String ROUTE = "AGENT";

    private final AgentDatabase db;
    private final ObjectMapper mapper;
    private final RepositoryReadService repository;
    private final ModelInvocationProperties modelProperties;
    private final TaskDispatchService dispatcher;

    private com.codereview.agent.execution.AgentBudgetProperties budget = new com.codereview.agent.execution.AgentBudgetProperties();
    private com.codereview.agent.tools.ToolLimitProperties toolLimits = new com.codereview.agent.tools.ToolLimitProperties();
    @org.springframework.beans.factory.annotation.Autowired
    public AgentTaskService(AgentDatabase db, ObjectMapper mapper, RepositoryReadService repository,
                            ModelInvocationProperties modelProperties, TaskDispatchService dispatcher,
                            com.codereview.agent.execution.AgentBudgetProperties budget,
                            com.codereview.agent.tools.ToolLimitProperties toolLimits) {
        this(db, mapper, repository, modelProperties, dispatcher);
        this.budget = budget; this.toolLimits = toolLimits;
    }
    public AgentTaskService(AgentDatabase db, ObjectMapper mapper,
                            RepositoryReadService repository,
                            ModelInvocationProperties modelProperties,
                            TaskDispatchService dispatcher) {
        this.db = db;
        this.mapper = mapper;
        this.repository = repository;
        this.modelProperties = modelProperties;
        this.dispatcher = dispatcher;
    }

    /** 兼容既有测试与手工装配：不注入调度时只创建任务，不派发。 */
    public AgentTaskService(AgentDatabase db, ObjectMapper mapper,
                            RepositoryReadService repository,
                            ModelInvocationProperties modelProperties) {
        this(db, mapper, repository, modelProperties, null);
    }

    public AgentTaskDtos.Created create(AgentTaskDtos.Create req, String idempotencyKey) {
        String key = normalizeKey(idempotencyKey);
        String requestHash = sha256(toJson(req));
        AgentProject project = requireProject(req.projectId());
        if (key != null) {
            AgentReviewTask existing = findByKey(project.getId(), key);
            if (existing == null && db.mapper(AgentReviewTaskMapper.class).selectDeletedByKey(project.getId(), key) != null) {
                throw new ApiException(ErrorCode.CONFLICT, "该幂等键对应的审查已删除，请使用新的幂等键");
            }
            if (existing != null) {
                return replay(existing, requestHash);
            }
        }

        String requirements = req.requirements() == null ? "" : req.requirements().strip();
        if (requirements.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "缺少本次调查要求");
        }
        AgentModelConfig model = requireAgentModel(req.modelConfigId());
        FrozenPrompt guidance = optionalGuidance(req.guidancePromptId(), req.guidancePromptVersionId());
        ScopePlan plan = resolveScope(req.scope(), project);
        FrozenReference reference = optionalReference(req.referenceResultId(), project.getId());

        AgentReviewTask task = new AgentReviewTask();
        task.setId(AgentIds.next());
        task.setProjectId(project.getId());
        task.setName(normalizeName(req.name(), task.getId()));
        task.setRoute(ROUTE);
        task.setSpecialistType(null);
        task.setState(TaskStates.QUEUED);
        task.setInputSnapshot(inputSnapshot(project, plan, requirements, guidance, reference));
        task.setConfigSnapshot(configSnapshot(model, guidance));
        task.setIdempotencyKey(key);
        task.setRequestHash(key == null ? null : requestHash);
        task.setRevision(0);
        LocalDateTime now = AgentTime.now();
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        task.setDeadlineAt(now.plusMinutes(budget.getDeadlineMinutes()));

        try {
            db.inTransaction(() -> {
                if (reference != null) {
                    var owner = db.mapper(AgentReviewTaskMapper.class).selectIncludingDeletedForUpdate(reference.taskId());
                    if (owner == null || Integer.valueOf(1).equals(owner.getIsDeleted())) {
                        throw new ApiException(ErrorCode.NOT_FOUND, "参考审查不存在或已删除");
                    }
                }
                db.mapper(AgentReviewTaskMapper.class).insert(task);
                insertQuota(task.getId());
                return null;
            });
        } catch (DuplicateKeyException race) {
            if (key != null) {
                AgentReviewTask existing = findByKey(project.getId(), key);
                if (existing != null) {
                    return replay(existing, requestHash);
                }
                if (db.mapper(AgentReviewTaskMapper.class).selectDeletedByKey(project.getId(), key) != null) {
                    throw new ApiException(ErrorCode.CONFLICT, "该幂等键对应的审查已删除，请使用新的幂等键");
                }
            }
            throw race;
        }
        if (dispatcher != null) {
            dispatcher.dispatch(task.getId());
        }
        return new AgentTaskDtos.Created(task.getId(), task.getState(), ROUTE,
                "/api/v2/review-tasks/" + task.getId());
    }

    /** 复制参数：只给可编辑项；重新观察 HEAD 与版本，不继承旧消息、旧 SHA 或额度。 */
    public AgentTaskDtos.CopyInput copyInput(String id) {
        AgentReviewTask task = requireTask(id);
        if (!ROUTE.equals(task.getRoute())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "该任务不是 Agent 任务：" + id);
        }
        JsonNode input = readJson(task.getInputSnapshot());
        JsonNode scope = input.path("scope");
        JsonNode config = readJson(task.getConfigSnapshot());
        List<String> paths = new ArrayList<>();
        for (JsonNode path : scope.path("paths")) {
            paths.add(path.asText());
        }
        List<String> commits = new ArrayList<>();
        for (JsonNode commit : scope.path("commits")) {
            commits.add(commit.path("commitSha").asText());
        }
        AgentTaskDtos.Scope copyScope = new AgentTaskDtos.Scope(
                scope.path("kind").asText("TEXT"), textOrNull(scope, "branch"),
                textOrNull(scope, "observedSha"), paths, commits, textOrNull(scope, "description"));
        String guidanceId = textOrNull(input.path("guidance"), "promptId");
        String guidanceVersionId = textOrNull(input.path("guidance"), "versionId");
        String referenceId = textOrNull(input.path("reference"), "resultId");
        return new AgentTaskDtos.CopyInput(task.getProjectId(),
                TaskNames.display(task.getName(), task.getId()),
                textOrNull(config.path("model"), "id"), guidanceId, guidanceVersionId, copyScope,
                textOrNull(input, "requirements"), referenceId,
                !"COMMITS".equals(copyScope.kind()));
    }

    private ScopePlan resolveScope(AgentTaskDtos.Scope scope, AgentProject project) {
        String kind = scope.kind().trim().toUpperCase();
        String description = scope.description() == null ? null : scope.description().strip();
        return switch (kind) {
            case "TEXT" -> {
                if (description == null || description.isBlank()) {
                    throw new ApiException(ErrorCode.VALIDATION_FAILED, "文字范围必须填写范围描述");
                }
                requireNoPathsOrCommits(scope);
                yield fromBranch(kind, scope, project, description, List.of());
            }
            case "FILES" -> {
                if (scope.paths().isEmpty()) {
                    throw new ApiException(ErrorCode.VALIDATION_FAILED, "文件范围至少选择一个文件或目录");
                }
                if (!scope.commits().isEmpty()) {
                    throw new ApiException(ErrorCode.VALIDATION_FAILED, "文件范围不能同时选择提交");
                }
                yield fromBranch(kind, scope, project, description, scope.paths());
            }
            case "COMMITS" -> {
                if (scope.commits().isEmpty()) {
                    throw new ApiException(ErrorCode.VALIDATION_FAILED, "提交范围至少选择一个提交");
                }
                if (!scope.paths().isEmpty()) {
                    throw new ApiException(ErrorCode.VALIDATION_FAILED, "提交范围不能同时选择文件");
                }
                yield fromCommits(scope, project, description);
            }
            default -> throw new ApiException(ErrorCode.VALIDATION_FAILED, "不支持的范围类型：" + kind);
        };
    }

    private static void requireNoPathsOrCommits(AgentTaskDtos.Scope scope) {
        if (!scope.paths().isEmpty() || !scope.commits().isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "文字范围不需要文件或提交");
        }
    }

    /** TEXT／FILES：重新核对分支 HEAD，路径必须在该固定版本中存在。 */
    private ScopePlan fromBranch(String kind, AgentTaskDtos.Scope scope, AgentProject project,
                                 String description, List<String> requestedPaths) {
        String branch = scope.branch() == null || scope.branch().isBlank()
                ? project.getCurrentBranch() : scope.branch().trim();
        String observed = requireFullSha(scope.observedSha(), "observedSha");
        String head = repository.resolveBranchHead(project.getId(), branch);
        if (head == null || !head.equalsIgnoreCase(observed)) {
            throw new ApiException(ErrorCode.SOURCE_MOVED,
                    "分支已移动，请刷新范围后重新选择",
                    Map.of("branch", branch, "observedSha", observed,
                            "currentHead", head == null ? "" : head));
        }
        List<String> paths = new ArrayList<>();
        if ("FILES".equals(kind)) {
            RepositorySnapshots.PathList tree = repository.listPaths(project.getId(), head);
            Set<String> all = new LinkedHashSet<>();
            tree.entries().forEach(entry -> all.add(entry.path()));
            for (String raw : requestedPaths) {
                String path = RepositoryPaths.requireSafePath(raw);
                boolean exists = all.contains(path)
                        || all.stream().anyMatch(candidate -> candidate.startsWith(path + "/"));
                if (!exists) {
                    throw new ApiException(ErrorCode.VALIDATION_FAILED,
                            "所选路径在该版本中不存在：" + path, Map.of("path", path));
                }
                if (!paths.contains(path)) {
                    paths.add(path);
                }
            }
        }
        List<VersionBinding> versions = List.of(new VersionBinding("V1", head, "HEAD"));
        return new ScopePlan(kind, description, branch, observed, head, paths, List.of(), versions);
    }

    /** COMMITS：逐项保存提交与第一父提交；非连续提交不合并成首尾净差异。 */
    private ScopePlan fromCommits(AgentTaskDtos.Scope scope, AgentProject project, String description) {
        Set<String> unique = new LinkedHashSet<>();
        for (String raw : scope.commits()) {
            unique.add(requireFullSha(raw, "commits"));
        }
        List<CommitPlan> commits = new ArrayList<>();
        Map<String, VersionBinding> versions = new LinkedHashMap<>();
        int comparison = 1;
        for (String sha : unique) {
            var detail = repository.commit(project.getId(), sha);
            String base = detail.baseSha();
            if (base != null) {
                RepositoryPaths.requireFixedSha(base);
            }
            String headRef = versionRef(versions, sha, "HEAD");
            String baseRef = base == null ? null : versionRef(versions, base, "BASE");
            commits.add(new CommitPlan(sha, base, headRef, baseRef,
                    "C" + comparison));
            comparison++;
        }
        List<VersionBinding> ordered = new ArrayList<>(versions.values());
        return new ScopePlan("COMMITS", description, null, null, null, List.of(), commits, ordered);
    }

    private static String versionRef(Map<String, VersionBinding> versions, String sha, String role) {
        for (VersionBinding binding : versions.values()) {
            if (binding.sha().equalsIgnoreCase(sha)) {
                return binding.ref();
            }
        }
        String ref = "V" + (versions.size() + 1);
        versions.put(ref, new VersionBinding(ref, sha, role));
        return ref;
    }

    private AgentTaskDtos.Created replay(AgentReviewTask existing, String requestHash) {
        if (!ROUTE.equals(existing.getRoute())) {
            throw new ApiException(ErrorCode.IDEMPOTENCY_CONFLICT,
                    "同一 Idempotency-Key 已用于其他路线", Map.of("taskId", existing.getId()));
        }
        if (existing.getRequestHash() != null && !existing.getRequestHash().equals(requestHash)) {
            throw new ApiException(ErrorCode.IDEMPOTENCY_CONFLICT,
                    "同一 Idempotency-Key 已用于不同请求", Map.of("taskId", existing.getId()));
        }
        return new AgentTaskDtos.Created(existing.getId(), existing.getState(), existing.getRoute(),
                "/api/v2/review-tasks/" + existing.getId());
    }

    private AgentModelConfig requireAgentModel(String modelConfigId) {
        AgentModelConfig model = db.mapper(AgentModelConfigMapper.class)
                .selectById(AgentIds.requireValid(modelConfigId, "模型 ID"));
        if (model == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, "模型不存在：" + modelConfigId);
        }
        if (!"OPENAI_CHAT".equals(model.getProtocol())) {
            throw new ApiException(ErrorCode.CONFIG_INCOMPLETE,
                    "Agent 首版只支持 OPENAI_CHAT 协议：" + model.getProtocol());
        }
        if (model.getContextWindow() == null || model.getContextWindow() <= 0
                || model.getMaxOutput() == null || model.getMaxOutput() <= 0) {
            throw new ApiException(ErrorCode.CONFIG_INCOMPLETE,
                    "模型缺少上下文窗口或最大输出，不能发起 Agent 任务", Map.of("modelId", model.getId()));
        }
        int safety = new com.codereview.agent.invocation.ModelCapacityService(modelProperties)
                .configuredSafetyReserve(model.getContextWindow());
        if ((long) Math.min(budget.getOutputTokens(), model.getMaxOutput()) + safety >= model.getContextWindow()) {
            throw new ApiException(ErrorCode.CONFIG_INCOMPLETE,
                    "Agent 输出预留与安全余量已占满模型窗口，请调整模型或运行配置");
        }
        List<AgentModelValidation> rows = db.mapper(AgentModelValidationMapper.class).selectList(
                new LambdaQueryWrapper<AgentModelValidation>()
                        .eq(AgentModelValidation::getModelConfigId, model.getId())
                        .eq(AgentModelValidation::getScope, AgentToolLoopValidator.SCOPE)
                        .orderByDesc(AgentModelValidation::getCreatedAt)
                        .last("limit 1"));
        if (rows.isEmpty()) {
            throw new ApiException(ErrorCode.CONFIG_INCOMPLETE,
                    "模型尚未通过 Agent 工具协议验证", Map.of("modelId", model.getId()));
        }
        AgentModelValidation validation = rows.get(0);
        if (!model.getRevision().equals(validation.getConfigRevision())
                || !"PASSED".equals(validation.getResult())) {
            throw new ApiException(ErrorCode.CONFIG_INCOMPLETE,
                    "模型的 Agent 工具协议验证已过期或未通过，请重新验证",
                    Map.of("modelId", model.getId(), "revision", model.getRevision()));
        }
        String expected = AgentToolLoopValidator.optionsHash(ModelSnapshot.from(model), toolLimits, budget);
        String actual = readJson(validation.getDetailJson()).path("optionsHash").asText("");
        if (!expected.equals(actual)) {
            throw new ApiException(ErrorCode.CONFIG_INCOMPLETE,
                    "模型配置或工具 schema 已变化，请重新执行 Agent 验证",
                    Map.of("modelId", model.getId()));
        }
        return model;
    }

    private FrozenPrompt optionalGuidance(String promptId, String versionId) {
        if (promptId == null || promptId.isBlank()) {
            if (versionId != null) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "选择版本前必须先选择指导提示词");
            }
            return null;
        }
        AgentPrompt prompt = db.mapper(AgentPromptMapper.class)
                .selectById(AgentIds.requireValid(promptId, "提示词 ID"));
        if (prompt == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, "提示词不存在：" + promptId);
        }
        if (!"AGENT".equals(prompt.getPurpose())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "只能选择 Agent 用途的指导提示词", Map.of("promptId", promptId,
                            "purpose", prompt.getPurpose()));
        }
        AgentPromptVersion version = PromptVersionResolver.resolve(db, prompt, versionId);
        return new FrozenPrompt(prompt.getId(), prompt.getName(), version.getId(),
                version.getVersionNo(), version.getContent());
    }

    private FrozenReference optionalReference(String resultId, String projectId) {
        if (resultId == null || resultId.isBlank()) {
            return null;
        }
        AgentReviewResult result = db.mapper(AgentReviewResultMapper.class)
                .selectById(AgentIds.requireValid(resultId, "结果 ID"));
        if (result == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, "参考结果不存在：" + resultId);
        }
        if (!"COMPLETE".equals(result.getCompleteness())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "只能参考完整结果：" + resultId);
        }
        AgentReviewTask task = db.mapper(AgentReviewTaskMapper.class).selectById(result.getTaskId());
        if (task == null || !projectId.equals(task.getProjectId())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "参考结果不属于该项目：" + resultId, Map.of("resultId", resultId));
        }
        return new FrozenReference(result.getId(), result.getTaskId(), result.getKind(),
                result.getSchemaVersion(), result.getCompleteness(), result.getBody());
    }

    private String inputSnapshot(AgentProject project, ScopePlan plan, String requirements,
                                 FrozenPrompt guidance, FrozenReference reference) {
        ObjectNode root = mapper.createObjectNode();
        root.put("version", 2);
        ObjectNode projectNode = root.putObject("project");
        projectNode.put("id", project.getId());
        projectNode.put("name", project.getName());
        projectNode.put("repoUrl", project.getRepoUrl());

        // 兼容既有任务详情的 source 视图；完整范围另存 scope。
        ObjectNode source = root.putObject("source");
        source.put("kind", plan.kind());
        putNullable(source, "branch", plan.branch());
        putNullable(source, "headSha", plan.headSha());
        putNullable(source, "baseSha", null);
        root.putArray("targets");

        ObjectNode scope = root.putObject("scope");
        scope.put("kind", plan.kind());
        putNullable(scope, "description", plan.description());
        putNullable(scope, "branch", plan.branch());
        putNullable(scope, "observedSha", plan.observedSha());
        putNullable(scope, "headSha", plan.headSha());
        ArrayNode paths = scope.putArray("paths");
        plan.paths().forEach(paths::add);
        ArrayNode commits = scope.putArray("commits");
        for (CommitPlan commit : plan.commits()) {
            ObjectNode node = commits.addObject();
            node.put("commitSha", commit.headSha());
            putNullable(node, "baseSha", commit.baseSha());
            node.put("root", commit.root());
            node.put("comparisonId", commit.comparisonId());
            node.put("headVersion", commit.headVersion());
            putNullable(node, "baseVersion", commit.baseVersion());
        }
        ArrayNode comparisons = scope.putArray("comparisons");
        for (ComparisonPlan comparison : plan.comparisons()) {
            ObjectNode node = comparisons.addObject();
            node.put("id", comparison.id());
            node.put("headSha", comparison.headSha());
            putNullable(node, "baseSha", comparison.baseSha());
        }
        ArrayNode versions = scope.putArray("versions");
        for (VersionBinding version : plan.versions()) {
            ObjectNode node = versions.addObject();
            node.put("version", version.ref());
            node.put("sha", version.sha());
            node.put("role", version.role());
        }
        root.put("requirements", requirements);
        if (guidance != null) {
            ObjectNode node = root.putObject("guidance");
            node.put("promptId", guidance.promptId());
            node.put("versionId", guidance.versionId());
            node.put("versionNo", guidance.versionNo());
        }
        if (reference != null) {
            ObjectNode node = root.putObject("reference");
            node.put("resultId", reference.resultId());
            node.put("taskId", reference.taskId());
            node.put("kind", reference.kind());
            node.put("schemaVersion", reference.schemaVersion());
            node.put("completeness", reference.completeness());
            node.put("body", reference.body());
        }
        return toJson(root);
    }

    private String configSnapshot(AgentModelConfig model, FrozenPrompt guidance) {
        ObjectNode root = mapper.createObjectNode();
        root.put("version", 3);
        ObjectNode modelNode = root.putObject("model");
        modelNode.put("id", model.getId());
        modelNode.put("name", model.getName());
        modelNode.put("modelName", model.getModelName());
        modelNode.put("protocol", model.getProtocol());
        modelNode.put("revision", model.getRevision());
        putNullable(modelNode, "contextWindow", model.getContextWindow());
        putNullable(modelNode, "maxOutput", model.getMaxOutput());
        modelNode.put("adapterVersion", PromptAssemblyService.ADAPTER_VERSION);
        modelNode.put("toolSchemaVersion", AgentToolSchema.version(toolLimits));
        modelNode.put("optionsHash", AgentToolLoopValidator.optionsHash(ModelSnapshot.from(model), toolLimits, budget));
        if (guidance != null) {
            ObjectNode prompt = root.putObject("prompt");
            prompt.put("id", guidance.promptId());
            prompt.put("name", guidance.promptName());
            prompt.put("versionId", guidance.versionId());
            prompt.put("versionNo", guidance.versionNo());
            prompt.put("content", guidance.content());
        }
        ObjectNode limits = root.putObject("limits");
        limits.put("modelTurns", budget.getModelTurns());
        limits.put("networkAttempts", budget.getNetworkAttempts());
        limits.put("toolAttempts", budget.getToolAttempts());
        limits.put("deadlineMinutes", budget.getDeadlineMinutes());
        limits.put("modelTimeoutSeconds", budget.getModelTimeoutSeconds());
        limits.put("outputTokens", budget.getOutputTokens());
        limits.put("contextStopRatio", budget.getContextStopRatio());
        limits.put("noProgressTurns", budget.getNoProgressTurns());
        long estimatedPerRequest = (long) Math.floor(model.getContextWindow() * budget.getContextStopRatio())
                + Math.min(budget.getOutputTokens(), model.getMaxOutput());
        limits.put("maxEstimatedTokenUnits", estimatedPerRequest * budget.getNetworkAttempts());
        limits.put("maxToolsPerTurn", budget.getMaxToolsPerTurn());
        limits.put("maxProtocolErrors", budget.getMaxProtocolErrors());
        root.set("toolLimits", mapper.valueToTree(toolLimits));
        root.set("invocationPolicy", mapper.valueToTree(com.codereview.agent.invocation.InvocationPolicy.from(modelProperties)));
        limits.put("toolSchemaVersion", AgentToolSchema.version(toolLimits));
        ObjectNode instructions = root.putObject("instructions");
        instructions.put("builtinVersion", "1");
        instructions.put("priority", "BUILTIN_THEN_REQUIREMENTS_THEN_GUIDANCE");
        instructions.put("availableTools", mapper.valueToTree(AgentToolNames.READ_ONLY));
        return toJson(root);
    }

    private void insertQuota(String taskId) {
        LocalDateTime now = AgentTime.now();
        AgentTaskQuota quota = new AgentTaskQuota();
        quota.setTaskId(taskId);
        quota.setHardLimit(budget.getNetworkAttempts());
        quota.setReserved(0);
        quota.setCreatedAt(now);
        quota.setUpdatedAt(now);
        db.mapper(AgentTaskQuotaMapper.class).insert(quota);
    }

    private AgentProject requireProject(String projectId) {
        AgentProject project = db.mapper(AgentProjectMapper.class)
                .selectById(AgentIds.requireValid(projectId, "项目 ID"));
        if (project == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, "项目不存在：" + projectId);
        }
        return project;
    }

    private AgentReviewTask findByKey(String projectId, String key) {
        List<AgentReviewTask> rows = db.mapper(AgentReviewTaskMapper.class).selectList(
                new LambdaQueryWrapper<AgentReviewTask>()
                        .eq(AgentReviewTask::getProjectId, projectId)
                        .eq(AgentReviewTask::getIdempotencyKey, key)
                        .last("limit 1"));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private AgentReviewTask requireTask(String id) {
        AgentReviewTask task = db.mapper(AgentReviewTaskMapper.class)
                .selectById(AgentIds.requireValid(id, "任务 ID"));
        if (task == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, "任务不存在：" + id);
        }
        return task;
    }

    private static String normalizeKey(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String trimmed = key.trim();
        if (trimmed.length() > 100) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Idempotency-Key 不能超过 100 字符");
        }
        return trimmed;
    }

    private static String normalizeName(String name, String id) {
        if (name == null || name.isBlank()) {
            return "Agent 任务 " + id;
        }
        return name.trim();
    }

    private static String requireFullSha(String value, String field) {
        try {
            return RepositoryPaths.requireFixedSha(value);
        } catch (ApiException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "提交必须使用完整 40 位 SHA", Map.of("field", field));
        }
    }

    private JsonNode readJson(String value) {
        if (value == null || value.isBlank()) {
            return mapper.createObjectNode();
        }
        try {
            return mapper.readTree(value);
        } catch (JsonProcessingException e) {
            return mapper.createObjectNode();
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() || value.asText().isBlank() ? null : value.asText();
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "快照序列化失败");
        }
    }

    private static void putNullable(ObjectNode node, String field, Object value) {
        if (value == null) {
            node.putNull(field);
        } else if (value instanceof Integer number) {
            node.put(field, number);
        } else {
            node.put(field, String.valueOf(value));
        }
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private record FrozenPrompt(String promptId, String promptName, String versionId,
                                Integer versionNo, String content) {
    }

    private record FrozenReference(String resultId, String taskId, String kind, String schemaVersion,
                                   String completeness, String body) {
    }

    private record VersionBinding(String ref, String sha, String role) {
    }

    private record CommitPlan(String headSha, String baseSha, String headVersion, String baseVersion,
                              String comparisonId) {
        boolean root() {
            return baseSha == null;
        }
    }

    private record ComparisonPlan(String id, String headSha, String baseSha) {
    }

    private record ScopePlan(String kind, String description, String branch, String observedSha,
                             String headSha, List<String> paths, List<CommitPlan> commits,
                             List<VersionBinding> versions) {

        List<ComparisonPlan> comparisons() {
            List<ComparisonPlan> items = new ArrayList<>();
            for (CommitPlan commit : commits) {
                items.add(new ComparisonPlan(commit.comparisonId(), commit.headSha(), commit.baseSha()));
            }
            return items;
        }
    }
}
