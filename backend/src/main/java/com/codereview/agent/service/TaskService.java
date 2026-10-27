package com.codereview.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.codereview.agent.api.ApiException;
import com.codereview.agent.api.ErrorCode;
import com.codereview.agent.api.PageResponse;
import com.codereview.agent.db.AgentDatabase;
import com.codereview.agent.dto.TaskDtos;
import com.codereview.agent.entity.AgentCallMaterial;
import com.codereview.agent.entity.AgentExecutionUnit;
import com.codereview.agent.entity.AgentIssue;
import com.codereview.agent.entity.AgentMaterial;
import com.codereview.agent.entity.AgentModelCall;
import com.codereview.agent.entity.AgentReviewResult;
import com.codereview.agent.entity.AgentModelConfig;
import com.codereview.agent.entity.AgentProject;
import com.codereview.agent.entity.AgentPrompt;
import com.codereview.agent.entity.AgentPromptVersion;
import com.codereview.agent.entity.AgentReviewTask;
import com.codereview.agent.entity.AgentTaskQuota;
import com.codereview.agent.entity.AgentTaskTarget;
import com.codereview.agent.execution.TaskDispatchService;
import com.codereview.agent.execution.TaskProperties;
import com.codereview.agent.execution.TaskStates;
import com.codereview.agent.invocation.ModelInvocationProperties;
import com.codereview.agent.mapper.AgentCallMaterialMapper;
import com.codereview.agent.mapper.AgentExecutionUnitMapper;
import com.codereview.agent.mapper.AgentIssueMapper;
import com.codereview.agent.mapper.AgentMaterialMapper;
import com.codereview.agent.mapper.AgentModelCallMapper;
import com.codereview.agent.mapper.AgentModelConfigMapper;
import com.codereview.agent.mapper.AgentReviewResultMapper;
import com.codereview.agent.mapper.AgentProjectMapper;
import com.codereview.agent.mapper.AgentPromptMapper;
import com.codereview.agent.mapper.AgentReviewTaskMapper;
import com.codereview.agent.mapper.AgentTaskQuotaMapper;
import com.codereview.agent.mapper.AgentTaskTargetMapper;
import com.codereview.agent.repository.RepositoryPaths;
import com.codereview.agent.repository.RepositoryReadService;
import com.codereview.agent.support.AgentIds;
import com.codereview.agent.support.AgentPaging;
import com.codereview.agent.support.AgentTime;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 任务 v2 服务：幂等创建、固定来源快照、目标记录、取消和读取。
 *
 * <p>创建时固定模型、通用规则版本和来源 SHA，后续配置修改只影响新任务。
 * 凭据不进入输入或配置快照；复制参数只返回可编辑项。
 */
@Service
@ConditionalOnProperty(prefix = "agent.datasource", name = "url")
public class TaskService {

    private final AgentDatabase db;
    private final ObjectMapper mapper;
    private final RepositoryReadService repository;
    private final TaskDispatchService dispatcher;
    private final ModelInvocationProperties modelProperties;
    private final TaskProperties taskProperties;

    public TaskService(AgentDatabase db, ObjectMapper mapper, RepositoryReadService repository,
                       TaskDispatchService dispatcher, ModelInvocationProperties modelProperties,
                       TaskProperties taskProperties) {
        this.db = db;
        this.mapper = mapper;
        this.repository = repository;
        this.dispatcher = dispatcher;
        this.modelProperties = modelProperties;
        this.taskProperties = taskProperties;
    }

    public TaskDtos.View create(TaskDtos.Create req, String idempotencyKey) {
        String key = normalizeKey(idempotencyKey);
        String requestHash = sha256(requestJson(req));
        AgentProject project = requireProject(req.projectId());
        if (key != null) {
            AgentReviewTask existing = findByKey(project.getId(), key);
            if (existing != null) {
                return replay(existing, requestHash);
            }
        }

        String specialistType = normalizeSpecialist(req.specialistType());
        String route = normalizeRoute(req.route());
        ResolvedConfig config = resolveConfig(req);
        ResolvedSource source = resolveSource(req.source(), project);
        List<NormalizedTarget> targets = normalizeTargets(req.targets());

        AgentReviewTask task = new AgentReviewTask();
        task.setId(AgentIds.next());
        task.setProjectId(project.getId());
        task.setName(normalizeName(req.name(), task.getId()));
        task.setRoute(route);
        task.setSpecialistType(specialistType);
        task.setState(TaskStates.QUEUED);
        task.setInputSnapshot(inputSnapshot(project, source, targets, Boolean.TRUE.equals(req.smartSplit())));
        task.setConfigSnapshot(configSnapshot(config));
        task.setIdempotencyKey(key);
        task.setRequestHash(key == null ? null : requestHash);
        task.setRevision(0);
        task.setCreatedAt(AgentTime.now());
        task.setUpdatedAt(AgentTime.now());
        task.setDeadlineAt(AgentTime.now().plusMinutes(Math.max(1, taskProperties.getDeadlineMinutes())));

        try {
            db.inTransaction(() -> {
                db.mapper(AgentReviewTaskMapper.class).insert(task);
                insertTargets(task, source, targets);
                insertQuota(task.getId());
                return null;
            });
        } catch (DuplicateKeyException race) {
            if (key != null) {
                AgentReviewTask existing = findByKey(project.getId(), key);
                if (existing != null) {
                    return replay(existing, requestHash);
                }
            }
            throw race;
        }
        dispatcher.dispatch(task.getId());
        return toView(task);
    }

    public PageResponse<TaskDtos.Summary> list(String projectId, String route, String type, String state,
                                               String q, String cursor, Integer limit) {
        return list(projectId, route, type, state, q, cursor, limit, null, null);
    }

    public PageResponse<TaskDtos.Summary> list(String projectId, String route, String type, String state,
                                               String q, String cursor, Integer limit, Integer pageNum, Integer pageSize) {
        LambdaQueryWrapper<AgentReviewTask> wrapper = new LambdaQueryWrapper<>();
        if (projectId != null && !projectId.isBlank()) {
            wrapper.eq(AgentReviewTask::getProjectId, projectId.trim());
        }
        if (q != null && !q.isBlank()) {
            String keyword = q.trim();
            wrapper.and(nested -> nested.likeRight(AgentReviewTask::getId, keyword.toUpperCase())
                    .or()
                    .like(AgentReviewTask::getName, keyword));
        }
        if (route != null && !route.isBlank()) {
            wrapper.eq(AgentReviewTask::getRoute, route.trim().toUpperCase());
        }
        if (type != null && !type.isBlank()) {
            wrapper.eq(AgentReviewTask::getSpecialistType, type.trim().toUpperCase());
        }
        if (state != null && !state.isBlank()) {
            wrapper.eq(AgentReviewTask::getState, state.trim().toUpperCase());
        }
        PageResponse<AgentReviewTask> page = AgentPaging.query(
                db.mapper(AgentReviewTaskMapper.class), wrapper, cursor, limit, pageNum, pageSize,
                AgentReviewTask::getCreatedAt, AgentReviewTask::getId);
        List<String> ids = page.items().stream().map(AgentReviewTask::getId).toList();
        Map<String, List<AgentTaskTarget>> targets = targetsByTask(ids);
        List<TaskDtos.Summary> summaries = page.items().stream()
                .map(task -> summary(task, targets.getOrDefault(task.getId(), List.of())))
                .toList();
        return page.withItems(summaries);
    }

    public TaskDtos.View detail(String id) {
        return toView(requireTask(id));
    }

    public TaskDtos.View cancel(String id, int expectedRevision) {
        AgentReviewTask task = requireTask(id);
        if (TaskStates.isTerminal(task.getState())) {
            return toView(task);
        }
        if (task.getRevision() != expectedRevision) {
            throw revisionConflict(task.getRevision());
        }
        LocalDateTime now = AgentTime.now();
        int affected = db.mapper(AgentReviewTaskMapper.class).update(null,
                new LambdaUpdateWrapper<AgentReviewTask>()
                        .eq(AgentReviewTask::getId, id)
                        .eq(AgentReviewTask::getRevision, expectedRevision)
                        .in(AgentReviewTask::getState, TaskStates.QUEUED, TaskStates.RUNNING)
                        .set(AgentReviewTask::getState, TaskStates.CANCELLED)
                        .set(AgentReviewTask::getStopReason, TaskStates.STOP_USER_CANCELLED)
                        .set(AgentReviewTask::getCancelRequestedAt, now)
                        .set(AgentReviewTask::getFinishedAt, now)
                        .set(AgentReviewTask::getUpdatedAt, now)
                        .setSql("revision = revision + 1"));
        if (affected == 0) {
            AgentReviewTask current = requireTask(id);
            if (TaskStates.isTerminal(current.getState())) {
                return toView(current);
            }
            throw revisionConflict(current.getRevision());
        }
        db.mapper(AgentTaskTargetMapper.class).update(null,
                new LambdaUpdateWrapper<AgentTaskTarget>()
                        .eq(AgentTaskTarget::getTaskId, id)
                        .notIn(AgentTaskTarget::getState, TaskStates.TARGET_TERMINAL)
                        .set(AgentTaskTarget::getState, TaskStates.CANCELLED)
                        .set(AgentTaskTarget::getErrorCode, TaskStates.STOP_USER_CANCELLED)
                        .set(AgentTaskTarget::getUpdatedAt, now)
                        .setSql("revision = revision + 1"));
        return toView(requireTask(id));
    }

    public TaskDtos.CopyInput copyInput(String id) {
        AgentReviewTask task = requireTask(id);
        JsonNode input = readJson(task.getInputSnapshot());
        JsonNode source = input.path("source");
        List<TaskDtos.TargetCopy> targets = new ArrayList<>();
        for (JsonNode target : input.path("targets")) {
            targets.add(new TaskDtos.TargetCopy(target.path("path").asText(),
                    textOrNull(target, "oldPath"), textOrNull(target, "note")));
        }
        JsonNode config = readJson(task.getConfigSnapshot());
        if (config.path("schemaVersion").asInt(0) != 2) {
            throw new ApiException(ErrorCode.CONFIG_INCOMPLETE,
                    "该试运行任务不支持复制，请重新创建");
        }
        String modelId = textOrNull(config.path("model"), "id");
        JsonNode prompt = config.path("prompt");
        String kind = source.path("kind").asText("FILES");
        return new TaskDtos.CopyInput(task.getProjectId(), task.getRoute(), task.getSpecialistType(),
                kind, textOrNull(source, "branch"), textOrNull(source, "commitSha"),
                textOrNull(source, "baseSha"), targets, modelId,
                textOrNull(prompt, "id"), textOrNull(prompt, "versionId"),
                input.path("smartSplit").asBoolean(false), "FILES".equals(kind),
                TaskNames.display(task.getName(), task.getId()));
    }

    public PageResponse<TaskDtos.CallSummary> calls(String taskId, String targetId, String unitId,
                                                    String cursor, Integer limit) {
        requireTask(taskId);
        int size = AgentPaging.limit(limit);
        LambdaQueryWrapper<AgentModelCall> wrapper = new LambdaQueryWrapper<AgentModelCall>()
                .select(AgentModelCall::getId, AgentModelCall::getTargetId, AgentModelCall::getUnitId,
                        AgentModelCall::getPurpose, AgentModelCall::getAttempt, AgentModelCall::getState,
                        AgentModelCall::getErrorCode, AgentModelCall::getCreatedAt,
                        AgentModelCall::getStartedAt, AgentModelCall::getFinishedAt)
                .eq(AgentModelCall::getTaskId, taskId);
        if (targetId != null && !targetId.isBlank()) {
            wrapper.eq(AgentModelCall::getTargetId, targetId.trim());
        }
        if (unitId != null && !unitId.isBlank()) {
            wrapper.eq(AgentModelCall::getUnitId, unitId.trim());
        }
        AgentPaging.applyCursor(wrapper, cursor, AgentModelCall::getCreatedAt, AgentModelCall::getId);
        AgentPaging.applyOrder(wrapper, AgentModelCall::getCreatedAt, AgentModelCall::getId);
        wrapper.last("limit " + (size + 1));
        PageResponse<AgentModelCall> page = AgentPaging.assemble(
                db.mapper(AgentModelCallMapper.class).selectList(wrapper), size,
                AgentModelCall::getCreatedAt, AgentModelCall::getId);
        List<String> unitIds = page.items().stream().map(AgentModelCall::getUnitId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        Map<String, AgentExecutionUnit> units = unitIds.isEmpty() ? Map.of()
                : db.mapper(AgentExecutionUnitMapper.class).selectList(
                        new LambdaQueryWrapper<AgentExecutionUnit>().in(AgentExecutionUnit::getId, unitIds))
                .stream().collect(Collectors.toMap(AgentExecutionUnit::getId, value -> value));
        List<TaskDtos.CallSummary> items = page.items().stream()
                .map(call -> new TaskDtos.CallSummary(call.getId(), call.getTargetId(), call.getUnitId(),
                        call.getPurpose(), call.getAttempt() == null ? 1 : call.getAttempt(),
                        call.getState(), call.getErrorCode(), call.getCreatedAt(),
                        call.getStartedAt(), call.getFinishedAt(),
                        call.getUnitId() != null && units.containsKey(call.getUnitId()) ? units.get(call.getUnitId()).getGeneration() : null,
                        call.getUnitId() != null && units.containsKey(call.getUnitId()) ? units.get(call.getUnitId()).getOrdinal() : null))
                .toList();
        return new PageResponse<>(items, page.nextCursor(), page.hasMore());
    }

    public TaskDtos.CallDetail callDetail(String callId, String part) {
        AgentModelCall call = db.mapper(AgentModelCallMapper.class)
                .selectById(AgentIds.requireValid(callId, "调用 ID"));
        if (call == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, "调用记录不存在：" + callId);
        }
        String normalized = part == null ? "response" : part.trim().toLowerCase();
        String content;
        if ("request".equals(normalized)) {
            content = call.getRequestBody();
        } else if ("response".equals(normalized)) {
            content = call.getResponseBody();
        } else {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "part 只能是 request 或 response",
                    Map.of("part", part));
        }
        boolean complete = content != null;
        String text = content == null ? "" : content;
        return new TaskDtos.CallDetail(call.getId(), normalized, text,
                text.getBytes(StandardCharsets.UTF_8).length, sha256(text), complete);
    }

    /**
     * 统一详情：按目标读取最近一次结果。结果正文和来源都是发布时保存的 JSON，
     * 不按当前提示词或最新源码重建。
     */
    public TaskDtos.ResultView result(String taskId, String targetId) {
        requireTask(taskId);
        requireTarget(taskId, targetId);
        List<AgentReviewResult> rows = db.mapper(AgentReviewResultMapper.class).selectList(
                new LambdaQueryWrapper<AgentReviewResult>()
                        .eq(AgentReviewResult::getTaskId, taskId)
                        .eq(AgentReviewResult::getTargetId, targetId)
                        .orderByDesc(AgentReviewResult::getCreatedAt)
                        .last("limit 1"));
        if (rows.isEmpty()) {
            throw new ApiException(ErrorCode.NOT_FOUND, "该目标还没有已发布结果");
        }
        AgentReviewResult row = rows.get(0);
        List<AgentIssue> issues = db.mapper(AgentIssueMapper.class).selectList(
                new LambdaQueryWrapper<AgentIssue>()
                        .eq(AgentIssue::getResultId, row.getId())
                        .orderByAsc(AgentIssue::getOrdinal));
        List<TaskDtos.IssueView> issueViews = issues.stream()
                .map(issue -> new TaskDtos.IssueView(issue.getId(), issue.getOrdinal() == null ? 0 : issue.getOrdinal(),
                        issue.getCategory(), issue.getSeverity(), issue.getTitle(), issue.getReason(),
                        issue.getSuggestion(), readStoredJson(issue.getLocations(), true),
                        readStoredJson(issue.getSourceUnits(), true)))
                .toList();
        return new TaskDtos.ResultView(row.getId(), row.getTargetId(), row.getKind(),
                row.getSchemaVersion(), row.getCompleteness(), row.getCreatedAt(),
                readStoredJson(row.getBody(), false), issueViews, readStoredJson(row.getProvenance(), false));
    }

    /** 目标实际发送过的材料；同一材料被多次调用引用时合并为一条。 */
    public List<TaskDtos.MaterialView> materials(String taskId, String targetId) {
        requireTask(taskId);
        requireTarget(taskId, targetId);
        List<AgentModelCall> calls = db.mapper(AgentModelCallMapper.class).selectList(
                new LambdaQueryWrapper<AgentModelCall>()
                        .select(AgentModelCall::getId)
                        .eq(AgentModelCall::getTaskId, taskId)
                        .eq(AgentModelCall::getTargetId, targetId)
                        .orderByAsc(AgentModelCall::getCreatedAt));
        if (calls.isEmpty()) {
            return List.of();
        }
        List<String> callIds = calls.stream().map(AgentModelCall::getId).toList();
        List<AgentCallMaterial> links = db.mapper(AgentCallMaterialMapper.class).selectList(
                new LambdaQueryWrapper<AgentCallMaterial>().in(AgentCallMaterial::getCallId, callIds));
        if (links.isEmpty()) {
            return List.of();
        }
        Map<String, List<AgentCallMaterial>> byMaterial = links.stream()
                .collect(Collectors.groupingBy(AgentCallMaterial::getMaterialId, LinkedHashMap::new,
                        Collectors.toList()));
        List<AgentMaterial> rows = db.mapper(AgentMaterialMapper.class).selectList(
                new LambdaQueryWrapper<AgentMaterial>().in(AgentMaterial::getId, byMaterial.keySet()));
        Map<String, AgentMaterial> byId = rows.stream()
                .collect(Collectors.toMap(AgentMaterial::getId, material -> material));
        List<TaskDtos.MaterialView> views = new ArrayList<>();
        for (Map.Entry<String, List<AgentCallMaterial>> entry : byMaterial.entrySet()) {
            AgentMaterial material = byId.get(entry.getKey());
            if (material == null) {
                continue;
            }
            views.add(materialView(material, entry.getValue()));
        }
        views.sort(Comparator.comparing(TaskDtos.MaterialView::path)
                .thenComparing(TaskDtos.MaterialView::side)
                .thenComparingInt(TaskDtos.MaterialView::startLine));
        return views;
    }

    /** 材料原文；只允许读取本任务调用实际关联过的材料。 */
    public TaskDtos.MaterialDetail material(String taskId, String materialId) {
        requireTask(taskId);
        String id = AgentIds.requireValid(materialId, "材料 ID");
        List<String> callIds = db.mapper(AgentModelCallMapper.class).selectList(
                        new LambdaQueryWrapper<AgentModelCall>()
                                .select(AgentModelCall::getId)
                                .eq(AgentModelCall::getTaskId, taskId))
                .stream().map(AgentModelCall::getId).toList();
        if (callIds.isEmpty()) {
            throw new ApiException(ErrorCode.NOT_FOUND, "材料不属于当前任务：" + materialId);
        }
        List<AgentCallMaterial> links = db.mapper(AgentCallMaterialMapper.class).selectList(
                new LambdaQueryWrapper<AgentCallMaterial>()
                        .eq(AgentCallMaterial::getMaterialId, id)
                        .in(AgentCallMaterial::getCallId, callIds));
        if (links.isEmpty()) {
            throw new ApiException(ErrorCode.NOT_FOUND, "材料不属于当前任务：" + materialId);
        }
        AgentMaterial row = db.mapper(AgentMaterialMapper.class).selectById(id);
        if (row == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, "材料不存在：" + materialId);
        }
        TaskDtos.MaterialView metadata = materialView(row, links);
        return new TaskDtos.MaterialDetail(metadata, row.getContent(), row.getContent() != null);
    }

    /** 一次实际调用的完整输入/输出/错误；调用必须属于本任务。 */
    public TaskDtos.CallDetailFull callDetailFull(String taskId, String callId) {
        requireTask(taskId);
        AgentModelCall call = db.mapper(AgentModelCallMapper.class)
                .selectById(AgentIds.requireValid(callId, "调用 ID"));
        if (call == null || !taskId.equals(call.getTaskId())) {
            throw new ApiException(ErrorCode.NOT_FOUND, "调用记录不属于当前任务：" + callId);
        }
        return fullCallView(call);
    }

    /** 报告等非任务调用的完整详情；保留保存时的正文与调用状态。 */
    public TaskDtos.CallDetailFull callDetailFull(String callId) {
        AgentModelCall call = db.mapper(AgentModelCallMapper.class)
                .selectById(AgentIds.requireValid(callId, "调用 ID"));
        if (call == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, "调用记录不存在：" + callId);
        }
        return fullCallView(call);
    }

    private TaskDtos.CallDetailFull fullCallView(AgentModelCall call) {
        return new TaskDtos.CallDetailFull(call.getId(), call.getTargetId(), call.getUnitId(),
                call.getPurpose(), call.getAttempt() == null ? 1 : call.getAttempt(),
                call.getState(), call.getErrorCode(), call.getErrorMessage(), call.getRequestBody(),
                call.getResponseBody(), call.getUsageJson(), call.getCreatedAt(), call.getStartedAt(),
                call.getFinishedAt());
    }

    private AgentTaskTarget requireTarget(String taskId, String targetId) {
        AgentTaskTarget target = db.mapper(AgentTaskTargetMapper.class)
                .selectById(AgentIds.requireValid(targetId, "目标 ID"));
        if (target == null || !taskId.equals(target.getTaskId())) {
            throw new ApiException(ErrorCode.NOT_FOUND, "目标不属于当前任务：" + targetId);
        }
        return target;
    }

    private TaskDtos.MaterialView materialView(AgentMaterial row, List<AgentCallMaterial> links) {
        boolean sent = links.stream().anyMatch(link -> "SENT".equals(link.getDisposition()));
        String role = links.stream().anyMatch(link -> "TARGET".equals(link.getRole()))
                ? "TARGET" : links.get(0).getRole();
        return new TaskDtos.MaterialView(row.getId(), row.getPath(), row.getSha(), row.getSide(),
                row.getStartLine(), row.getEndLine(), role, sent ? "SENT" : links.get(0).getDisposition(),
                row.getCompleteness(), row.getDiagnostic(), row.getContentHash(), byteSize(row.getContent()), sent);
    }

    private JsonNode readStoredJson(String value, boolean array) {
        try {
            JsonNode node = value == null ? null : mapper.readTree(value);
            if (node != null && (array ? node.isArray() : node.isObject())) return node;
        } catch (JsonProcessingException ignored) {
            // 保存数据损坏不能伪装为空结果。
        }
        throw new ApiException(ErrorCode.INTERNAL_ERROR, "保存的结果或来源数据损坏，无法读取");
    }

    private static int byteSize(String content) {
        return content == null ? 0 : content.getBytes(StandardCharsets.UTF_8).length;
    }

    private TaskDtos.View replay(AgentReviewTask existing, String requestHash) {
        if (existing.getRequestHash() != null && !existing.getRequestHash().equals(requestHash)) {
            throw new ApiException(ErrorCode.IDEMPOTENCY_CONFLICT,
                    "同一 Idempotency-Key 已用于不同请求", Map.of("taskId", existing.getId()));
        }
        return toView(existing);
    }

    private AgentReviewTask findByKey(String projectId, String key) {
        List<AgentReviewTask> rows = db.mapper(AgentReviewTaskMapper.class).selectList(
                new LambdaQueryWrapper<AgentReviewTask>()
                        .eq(AgentReviewTask::getProjectId, projectId)
                        .eq(AgentReviewTask::getIdempotencyKey, key)
                        .last("limit 1"));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private ResolvedConfig resolveConfig(TaskDtos.Create req) {
        String modelId = req.modelConfigId() == null || req.modelConfigId().isBlank()
                ? null : req.modelConfigId().trim();
        if (modelId == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "必须指定模型");
        }
        AgentModelConfig model = db.mapper(AgentModelConfigMapper.class)
                .selectById(AgentIds.requireValid(modelId, "模型 ID"));
        if (model == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, "模型不存在：" + modelId);
        }
        AgentPrompt prompt = null;
        AgentPromptVersion promptVersion = null;
        boolean hasPromptId = req.promptId() != null && !req.promptId().isBlank();
        boolean hasVersionId = req.promptVersionId() != null && !req.promptVersionId().isBlank();
        if (hasPromptId != hasVersionId) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "选择通用规则时必须同时指定规则 ID 和具体版本");
        }
        if (hasPromptId) {
            prompt = db.mapper(AgentPromptMapper.class)
                    .selectById(AgentIds.requireValid(req.promptId(), "通用规则 ID"));
            if (prompt == null) {
                throw new ApiException(ErrorCode.NOT_FOUND, "通用规则不存在：" + req.promptId());
            }
            if (!"SPECIALIST".equals(prompt.getPurpose())) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "所选通用规则不属于专项用途");
            }
            promptVersion = PromptVersionResolver.resolve(db, prompt,
                    AgentIds.requireValid(req.promptVersionId(), "通用规则版本 ID"));
        }
        return new ResolvedConfig(model, prompt, promptVersion);
    }

    private ResolvedSource resolveSource(TaskDtos.Source source, AgentProject project) {
        String kind = source.kind() == null ? "" : source.kind().trim().toUpperCase();
        if ("FILES".equals(kind)) {
            String branch = source.branch() == null || source.branch().isBlank()
                    ? project.getCurrentBranch() : source.branch().trim();
            if (branch == null || branch.isBlank()) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "文件来源缺少分支");
            }
            if (source.observedSha() == null || source.observedSha().isBlank()) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED,
                        "文件来源缺少 observedSha；发起前必须先取得确认过的提交");
            }
            String observed = RepositoryPaths.requireFixedSha(source.observedSha());
            String head = repository.resolveBranchHead(project.getId(), branch);
            if (head == null || !head.equalsIgnoreCase(observed)) {
                throw new ApiException(ErrorCode.SOURCE_MOVED,
                        "分支 " + branch + " 在确认后已移动，请刷新范围后重新提交",
                        Map.of("branch", branch, "observedSha", observed,
                                "currentSha", head == null ? "UNKNOWN" : head));
            }
            return new ResolvedSource("FILES", branch, head.toLowerCase(), null);
        }
        if ("DIFF".equals(kind)) {
            String commit = RepositoryPaths.requireFixedSha(source.commitSha());
            String base = source.baseSha() == null || source.baseSha().isBlank()
                    ? null : RepositoryPaths.requireFixedSha(source.baseSha());
            if (base != null && base.equalsIgnoreCase(commit)) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "比较基线与目标提交不能相同");
            }
            String branch = source.branch() == null ? null : source.branch().trim();
            return new ResolvedSource("DIFF", branch, commit, base);
        }
        throw new ApiException(ErrorCode.VALIDATION_FAILED, "source.kind 只能是 FILES 或 DIFF",
                Map.of("kind", source.kind()));
    }

    private List<NormalizedTarget> normalizeTargets(List<TaskDtos.Target> targets) {
        List<NormalizedTarget> normalized = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (TaskDtos.Target target : targets) {
            String path = RepositoryPaths.requireSafePath(target.path());
            if (!seen.add(path)) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "目标文件重复：" + path);
            }
            String oldPath = target.oldPath() == null || target.oldPath().isBlank()
                    ? null : RepositoryPaths.requireSafePath(target.oldPath());
            String note = target.note() == null ? null : target.note().trim();
            if (note != null && note.length() > 4000) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "逐文件说明过长：" + path);
            }
            normalized.add(new NormalizedTarget(path, oldPath, note == null || note.isEmpty() ? null : note));
        }
        return normalized;
    }

    private void insertTargets(AgentReviewTask task, ResolvedSource source,
                               List<NormalizedTarget> targets) {
        LocalDateTime now = AgentTime.now();
        int ordinal = 1;
        for (NormalizedTarget target : targets) {
            AgentTaskTarget row = new AgentTaskTarget();
            row.setId(AgentIds.next());
            row.setTaskId(task.getId());
            row.setOrdinal(ordinal++);
            row.setPath(target.path());
            row.setOldPath(target.oldPath());
            row.setBaseSha(source.baseSha());
            row.setHeadSha(source.headSha());
            row.setNote(target.note());
            row.setState(TaskStates.QUEUED);
            row.setRevision(0);
            row.setCreatedAt(now);
            row.setUpdatedAt(now);
            db.mapper(AgentTaskTargetMapper.class).insert(row);
        }
    }

    private void insertQuota(String taskId) {
        LocalDateTime now = AgentTime.now();
        AgentTaskQuota quota = new AgentTaskQuota();
        quota.setTaskId(taskId);
        quota.setHardLimit(modelProperties.getTaskCallLimit());
        quota.setReserved(0);
        quota.setCreatedAt(now);
        quota.setUpdatedAt(now);
        db.mapper(AgentTaskQuotaMapper.class).insert(quota);
    }

    private String inputSnapshot(AgentProject project, ResolvedSource source,
                                 List<NormalizedTarget> targets, boolean smartSplit) {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode projectNode = root.putObject("project");
        projectNode.put("id", project.getId());
        projectNode.put("name", project.getName());
        projectNode.put("repoUrl", project.getRepoUrl());
        ObjectNode sourceNode = root.putObject("source");
        sourceNode.put("kind", source.kind());
        putNullable(sourceNode, "branch", source.branch());
        sourceNode.put("headSha", source.headSha());
        putNullable(sourceNode, "baseSha", source.baseSha());
        putNullable(sourceNode, "commitSha", "DIFF".equals(source.kind()) ? source.headSha() : null);
        var targetArray = root.putArray("targets");
        for (NormalizedTarget target : targets) {
            ObjectNode node = targetArray.addObject();
            node.put("path", target.path());
            putNullable(node, "oldPath", target.oldPath());
            putNullable(node, "note", target.note());
        }
        root.put("smartSplit", smartSplit);
        return toJson(root);
    }

    private String configSnapshot(ResolvedConfig config) {
        ObjectNode root = mapper.createObjectNode();
        root.put("schemaVersion", 2);
        AgentModelConfig model = config.model();
        ObjectNode modelNode = root.putObject("model");
        modelNode.put("id", model.getId());
        modelNode.put("name", model.getName());
        modelNode.put("baseUrl", model.getBaseUrl());
        modelNode.put("modelName", model.getModelName());
        putNullable(modelNode, "contextWindow", model.getContextWindow());
        putNullable(modelNode, "maxOutput", model.getMaxOutput());
        putNullable(modelNode, "tokenizer", model.getTokenizer());
        modelNode.put("protocol", model.getProtocol());
        modelNode.put("revision", model.getRevision());
        if (config.prompt() != null) {
            ObjectNode promptNode = root.putObject("prompt");
            promptNode.put("id", config.prompt().getId());
            promptNode.put("name", config.prompt().getName());
            if (config.promptVersion() != null) {
                promptNode.put("versionId", config.promptVersion().getId());
                promptNode.put("versionNo", config.promptVersion().getVersionNo());
                promptNode.put("content", config.promptVersion().getContent());
            }
        }
        return toJson(root);
    }

    private TaskDtos.View toView(AgentReviewTask task) {
        List<AgentTaskTarget> targets = db.mapper(AgentTaskTargetMapper.class).selectList(
                new LambdaQueryWrapper<AgentTaskTarget>()
                        .eq(AgentTaskTarget::getTaskId, task.getId())
                        .orderByAsc(AgentTaskTarget::getOrdinal));
        JsonNode input = readJson(task.getInputSnapshot());
        JsonNode source = input.path("source");
        JsonNode config = readJson(task.getConfigSnapshot());
        JsonNode model = config.path("model");
        JsonNode prompt = config.path("prompt");
        TaskDtos.ConfigView configView = new TaskDtos.ConfigView(
                textOrNull(model, "id"), textOrNull(model, "name"), integerOrNull(model, "revision"),
                textOrNull(prompt, "id"), textOrNull(prompt, "name"),
                textOrNull(prompt, "versionId"), integerOrNull(prompt, "versionNo"),
                textOrNull(prompt, "content"));
        Map<String, Integer> issueCounts = targetIssueCounts(task, targets);
        List<TaskDtos.TargetView> targetViews = targets.stream()
                .map(target -> new TaskDtos.TargetView(target.getId(), target.getOrdinal(),
                        target.getPath(), target.getOldPath(), target.getBaseSha(), target.getHeadSha(),
                        target.getNote(), target.getState(), target.getErrorCode(),
                        target.getRevision() == null ? 0 : target.getRevision(),
                        target.getCreatedAt(), target.getUpdatedAt(), issueCounts.get(target.getId())))
                .toList();
        return new TaskDtos.View(task.getId(), TaskNames.display(task.getName(), task.getId()),
                task.getProjectId(), task.getRoute(),
                task.getSpecialistType(), task.getState(), task.getStopReason(),
                source.path("kind").asText(null), textOrNull(source, "branch"),
                textOrNull(source, "headSha"), textOrNull(source, "baseSha"),
                input.path("smartSplit").asBoolean(false), task.getRevision(), task.getCreatedAt(),
                task.getUpdatedAt(), task.getStartedAt(), task.getFinishedAt(), task.getDeadlineAt(),
                task.getCancelRequestedAt(), targetViews, configView);
    }

    /** 按最近发布的完整问题结果批量计数；没有完整结果时保留 null，不能补成零。 */
    private Map<String, Integer> targetIssueCounts(AgentReviewTask task, List<AgentTaskTarget> targets) {
        if (!"ISSUE".equals(task.getSpecialistType()) || targets.isEmpty()) {
            return Map.of();
        }
        List<AgentReviewResult> results = db.mapper(AgentReviewResultMapper.class).selectList(
                new LambdaQueryWrapper<AgentReviewResult>()
                        .select(AgentReviewResult::getId, AgentReviewResult::getTargetId,
                                AgentReviewResult::getKind, AgentReviewResult::getCompleteness)
                        .eq(AgentReviewResult::getTaskId, task.getId())
                        .in(AgentReviewResult::getTargetId, targets.stream().map(AgentTaskTarget::getId).toList())
                        .orderByDesc(AgentReviewResult::getCreatedAt));
        Map<String, AgentReviewResult> latest = new LinkedHashMap<>();
        results.forEach(result -> latest.putIfAbsent(result.getTargetId(), result));
        List<String> resultIds = latest.values().stream()
                .filter(result -> "ISSUE".equals(result.getKind()) && "COMPLETE".equals(result.getCompleteness()))
                .map(AgentReviewResult::getId).toList();
        if (resultIds.isEmpty()) {
            return Map.of();
        }
        Map<String, Integer> countsByResult = new LinkedHashMap<>();
        db.mapper(AgentIssueMapper.class).selectList(new LambdaQueryWrapper<AgentIssue>()
                        .select(AgentIssue::getResultId).in(AgentIssue::getResultId, resultIds))
                .forEach(issue -> countsByResult.merge(issue.getResultId(), 1, Integer::sum));
        Map<String, Integer> countsByTarget = new LinkedHashMap<>();
        latest.forEach((targetId, result) -> {
            if ("ISSUE".equals(result.getKind()) && "COMPLETE".equals(result.getCompleteness())) {
                countsByTarget.put(targetId, countsByResult.getOrDefault(result.getId(), 0));
            }
        });
        return countsByTarget;
    }

    private TaskDtos.Summary summary(AgentReviewTask task, List<AgentTaskTarget> targets) {
        int succeeded = 0;
        int failed = 0;
        int running = 0;
        int queued = 0;
        for (AgentTaskTarget target : targets) {
            switch (target.getState()) {
                case TaskStates.SUCCEEDED -> succeeded++;
                case TaskStates.FAILED, TaskStates.PARTIAL, TaskStates.CANCELLED -> failed++;
                case TaskStates.RUNNING, TaskStates.PREPARING -> running++;
                default -> queued++;
            }
        }
        return new TaskDtos.Summary(task.getId(), TaskNames.display(task.getName(), task.getId()),
                task.getProjectId(), task.getRoute(),
                task.getSpecialistType(), task.getState(), task.getStopReason(),
                task.getCreatedAt(), task.getDeadlineAt(), targets.size(), succeeded, failed,
                running, queued);
    }

    private Map<String, List<AgentTaskTarget>> targetsByTask(List<String> taskIds) {
        if (taskIds.isEmpty()) {
            return Map.of();
        }
        List<AgentTaskTarget> rows = db.mapper(AgentTaskTargetMapper.class).selectList(
                new LambdaQueryWrapper<AgentTaskTarget>()
                        .in(AgentTaskTarget::getTaskId, taskIds)
                        .orderByAsc(AgentTaskTarget::getOrdinal));
        return rows.stream().collect(Collectors.groupingBy(AgentTaskTarget::getTaskId,
                LinkedHashMap::new, Collectors.toList()));
    }

    private AgentProject requireProject(String projectId) {
        AgentProject project = db.mapper(AgentProjectMapper.class)
                .selectById(AgentIds.requireValid(projectId, "项目 ID"));
        if (project == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, "项目不存在：" + projectId);
        }
        return project;
    }

    private AgentReviewTask requireTask(String id) {
        AgentReviewTask task = db.mapper(AgentReviewTaskMapper.class)
                .selectById(AgentIds.requireValid(id, "任务 ID"));
        if (task == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, "任务不存在：" + id);
        }
        return task;
    }

    private static String normalizeName(String requested, String id) {
        if (requested == null || requested.isBlank()) {
            return TaskNames.display(null, id);
        }
        String trimmed = requested.trim();
        if (trimmed.length() > 200) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "审查名称不能超过 200 个字符",
                    Map.of("field", "name"));
        }
        return trimmed;
    }

    private static String normalizeSpecialist(String value) {
        String type = value == null ? "" : value.trim().toUpperCase();
        if (!List.of("ISSUE", "PATTERN", "COUPLING").contains(type)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "专项类型只能是 ISSUE、PATTERN 或 COUPLING", Map.of("specialistType", value));
        }
        return type;
    }

    private static String normalizeRoute(String value) {
        String route = value == null || value.isBlank() ? "SPECIALIST" : value.trim().toUpperCase();
        if ("AGENT".equals(route)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Agent 任务在 P33 接入，当前只支持专项");
        }
        if (!"SPECIALIST".equals(route)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "route 只能是 SPECIALIST");
        }
        return route;
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

    private static ApiException revisionConflict(int currentRevision) {
        return new ApiException(ErrorCode.REVISION_CONFLICT, "任务已被其他操作修改，请刷新后重试",
                Map.of("currentRevision", currentRevision));
    }

    private String requestJson(TaskDtos.Create req) {
        try {
            return mapper.writeValueAsString(req);
        } catch (JsonProcessingException e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "请求序列化失败");
        }
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "快照序列化失败");
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

    private static void putNullable(ObjectNode node, String field, Object value) {
        if (value == null) {
            node.putNull(field);
        } else if (value instanceof Integer number) {
            node.put(field, number);
        } else {
            node.put(field, String.valueOf(value));
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() || value.asText().isBlank() ? null : value.asText();
    }

    private static Integer integerOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.asInt() : null;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private record ResolvedConfig(AgentModelConfig model, AgentPrompt prompt,
                                  AgentPromptVersion promptVersion) {
    }

    private record ResolvedSource(String kind, String branch, String headSha, String baseSha) {
    }

    private record NormalizedTarget(String path, String oldPath, String note) {
    }
}
