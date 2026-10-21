package com.codereview.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.codereview.agent.api.ApiException;
import com.codereview.agent.api.ErrorCode;
import com.codereview.agent.api.PageResponse;
import com.codereview.agent.db.AgentDatabase;
import com.codereview.agent.dto.ReportDtos;
import com.codereview.agent.entity.AgentFeedback;
import com.codereview.agent.entity.AgentIssue;
import com.codereview.agent.entity.AgentMaterial;
import com.codereview.agent.entity.AgentProject;
import com.codereview.agent.entity.AgentReport;
import com.codereview.agent.entity.AgentReportSource;
import com.codereview.agent.entity.AgentReviewResult;
import com.codereview.agent.entity.AgentReviewTask;
import com.codereview.agent.entity.AgentTaskTarget;
import com.codereview.agent.mapper.AgentFeedbackMapper;
import com.codereview.agent.mapper.AgentIssueMapper;
import com.codereview.agent.mapper.AgentMaterialMapper;
import com.codereview.agent.mapper.AgentProjectMapper;
import com.codereview.agent.mapper.AgentReportMapper;
import com.codereview.agent.mapper.AgentReportSourceMapper;
import com.codereview.agent.mapper.AgentReviewResultMapper;
import com.codereview.agent.mapper.AgentReviewTaskMapper;
import com.codereview.agent.mapper.AgentTaskTargetMapper;
import com.codereview.agent.support.AgentIds;
import com.codereview.agent.support.AgentPaging;
import com.codereview.agent.support.AgentTime;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 报告候选与输入冻结。
 *
 * <p>候选只来自 {@code completeness=COMPLETE} 的已发布结果，任务即使部分完成或已取消，
 * 其中完整文件仍可选。冻结时把结果正文、问题、来源材料引用、任务／目标版本和当时反馈
 * 一并写入 {@code report_source.source_snapshot}；之后读取报告来源不联查最新数据。
 * 本阶段不选择模型、不组装提示词、不生成正文。
 */
@Service
@ConditionalOnProperty(prefix = "agent.datasource", name = "url")
public class ReportInputService {

    static final String STATE_INPUT_FROZEN = "INPUT_FROZEN";
    private static final int SNAPSHOT_VERSION = 1;
    private static final Set<String> KINDS = Set.of("ISSUE", "PATTERN", "COUPLING", "AGENT");

    private final AgentDatabase db;
    private final ObjectMapper mapper;

    public ReportInputService(AgentDatabase db, ObjectMapper mapper) {
        this.db = db;
        this.mapper = mapper;
    }

    public PageResponse<ReportDtos.Candidate> candidates(String projectId, String taskId, String kind,
                                                         String cursor, Integer limit) {
        int size = AgentPaging.limit(limit);
        LambdaQueryWrapper<AgentReviewResult> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AgentReviewResult::getCompleteness, "COMPLETE");
        if (projectId != null && !projectId.isBlank()) {
            String id = AgentIds.requireValid(projectId.trim(), "项目 ID");
            wrapper.apply("task_id IN (SELECT id FROM review_task WHERE project_id = {0})", id);
        }
        if (taskId != null && !taskId.isBlank()) {
            wrapper.eq(AgentReviewResult::getTaskId, AgentIds.requireValid(taskId.trim(), "任务 ID"));
        }
        if (kind != null && !kind.isBlank()) {
            wrapper.eq(AgentReviewResult::getKind, requireKind(kind));
        }
        AgentPaging.applyCursor(wrapper, cursor, AgentReviewResult::getCreatedAt, AgentReviewResult::getId);
        AgentPaging.applyOrder(wrapper, AgentReviewResult::getCreatedAt, AgentReviewResult::getId);
        wrapper.last("limit " + (size + 1));
        PageResponse<AgentReviewResult> page = AgentPaging.assemble(
                db.mapper(AgentReviewResultMapper.class).selectList(wrapper), size,
                AgentReviewResult::getCreatedAt, AgentReviewResult::getId);

        List<String> resultIds = page.items().stream().map(AgentReviewResult::getId).toList();
        Map<String, AgentReviewTask> tasks = byId(page.items().stream()
                        .map(AgentReviewResult::getTaskId).filter(java.util.Objects::nonNull).distinct().toList(),
                ids -> db.mapper(AgentReviewTaskMapper.class).selectList(
                        new LambdaQueryWrapper<AgentReviewTask>().in(AgentReviewTask::getId, ids)),
                AgentReviewTask::getId);
        Map<String, AgentTaskTarget> targets = byId(page.items().stream()
                        .map(AgentReviewResult::getTargetId).filter(java.util.Objects::nonNull).distinct().toList(),
                ids -> db.mapper(AgentTaskTargetMapper.class).selectList(
                        new LambdaQueryWrapper<AgentTaskTarget>().in(AgentTaskTarget::getId, ids)),
                AgentTaskTarget::getId);
        Map<String, AgentProject> projects = byId(tasks.values().stream()
                        .map(AgentReviewTask::getProjectId).filter(java.util.Objects::nonNull).distinct().toList(),
                ids -> db.mapper(AgentProjectMapper.class).selectList(
                        new LambdaQueryWrapper<AgentProject>().in(AgentProject::getId, ids)),
                AgentProject::getId);
        Map<String, Integer> issueCounts = issueCounts(resultIds);
        Map<String, AgentFeedback> resultFeedback = resultFeedback(page.items().stream()
                .filter(result -> !"ISSUE".equals(result.getKind()))
                .map(AgentReviewResult::getId).toList());

        List<ReportDtos.Candidate> items = new ArrayList<>();
        for (AgentReviewResult result : page.items()) {
            AgentReviewTask task = tasks.get(result.getTaskId());
            if (task == null) {
                // 任务缺失的结果不能说明来源，宁可不列为候选，也不返回半条材料。
                continue;
            }
            AgentTaskTarget target = result.getTargetId() == null ? null : targets.get(result.getTargetId());
            AgentProject project = projects.get(task.getProjectId());
            TaskSource source = source(task);
            AgentFeedback feedback = resultFeedback.get(result.getId());
            items.add(new ReportDtos.Candidate(result.getId(), result.getTaskId(), result.getTargetId(),
                    result.getKind(), task.getProjectId(), project == null ? task.getProjectId() : project.getName(),
                    task.getRoute(), task.getState(), source.kind(),
                    target == null ? null : target.getPath(), target == null ? null : target.getOldPath(),
                    target == null ? null : target.getHeadSha(), target == null ? null : target.getBaseSha(),
                    source.branch(), result.getCreatedAt(), issueCounts.getOrDefault(result.getId(), 0),
                    feedback == null ? null : new ReportDtos.Feedback(feedback.getAssessment(),
                            feedback.getComment(), feedback.getRevision() == null ? 0 : feedback.getRevision(),
                            feedback.getUpdatedAt())));
        }
        return new PageResponse<>(items, page.nextCursor(), page.hasMore());
    }

    public ReportDtos.FrozenInput freeze(ReportDtos.FreezeRequest request) {
        // 新库默认的可重复读事务覆盖读取及写入，避免问题级、结果级反馈来自不同的读取时刻。
        return db.inTransaction(() -> freezeInTransaction(request));
    }

    private ReportDtos.FrozenInput freezeInTransaction(ReportDtos.FreezeRequest request) {
        String title = request.title().trim();
        List<String> ids = request.resultIds().stream().map(String::trim).toList();
        if (ids.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "至少选择一个审查结果");
        }
        if (new HashSet<>(ids).size() != ids.size()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "同一审查结果不能重复选择");
        }
        for (String id : ids) {
            AgentIds.requireValid(id, "审查结果 ID");
        }
        List<AgentReviewResult> rows = db.mapper(AgentReviewResultMapper.class).selectList(
                new LambdaQueryWrapper<AgentReviewResult>().in(AgentReviewResult::getId, ids));
        Map<String, AgentReviewResult> byId = rows.stream()
                .collect(Collectors.toMap(AgentReviewResult::getId, Function.identity()));
        List<String> missing = ids.stream().filter(id -> !byId.containsKey(id)).toList();
        if (!missing.isEmpty()) {
            throw new ApiException(ErrorCode.NOT_FOUND, "部分审查结果不存在，不能冻结",
                    Map.of("resultIds", missing));
        }
        List<String> incomplete = ids.stream()
                .filter(id -> !"COMPLETE".equals(byId.get(id).getCompleteness())).toList();
        if (!incomplete.isEmpty()) {
            throw new ApiException(ErrorCode.CONFLICT, "只允许选择已完整发布的审查结果",
                    Map.of("resultIds", incomplete));
        }

        List<String> taskIds = rows.stream().map(AgentReviewResult::getTaskId).distinct().toList();
        List<String> targetIds = rows.stream().map(AgentReviewResult::getTargetId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        Map<String, AgentReviewTask> tasks = taskIds.isEmpty() ? Map.of()
                : db.mapper(AgentReviewTaskMapper.class).selectList(
                        new LambdaQueryWrapper<AgentReviewTask>().in(AgentReviewTask::getId, taskIds))
                .stream().collect(Collectors.toMap(AgentReviewTask::getId, Function.identity()));
        Map<String, AgentTaskTarget> targets = targetIds.isEmpty() ? Map.of()
                : db.mapper(AgentTaskTargetMapper.class).selectList(
                        new LambdaQueryWrapper<AgentTaskTarget>().in(AgentTaskTarget::getId, targetIds))
                .stream().collect(Collectors.toMap(AgentTaskTarget::getId, Function.identity()));
        List<String> projectIds = tasks.values().stream().map(AgentReviewTask::getProjectId)
                .distinct().toList();
        Map<String, AgentProject> projects = projectIds.isEmpty() ? Map.of()
                : db.mapper(AgentProjectMapper.class).selectList(
                        new LambdaQueryWrapper<AgentProject>().in(AgentProject::getId, projectIds))
                .stream().collect(Collectors.toMap(AgentProject::getId, Function.identity()));
        Map<String, List<AgentIssue>> issuesByResult = issuesByResult(ids);
        Map<String, AgentFeedback> issueFeedback = issueFeedback(issuesByResult);
        Map<String, AgentFeedback> resultFeedback = resultFeedback(rows.stream()
                .filter(result -> !"ISSUE".equals(result.getKind()))
                .map(AgentReviewResult::getId).toList());

        LocalDateTime frozenAt = AgentTime.now();
        List<ReportDtos.FrozenSource> sources = new ArrayList<>();
        for (int index = 0; index < ids.size(); index++) {
            AgentReviewResult result = byId.get(ids.get(index));
            AgentReviewTask task = tasks.get(result.getTaskId());
            if (task == null) {
                throw new ApiException(ErrorCode.CONFLICT, "审查结果缺少所属任务，不能冻结",
                        Map.of("resultId", result.getId()));
            }
            AgentTaskTarget target = result.getTargetId() == null ? null : targets.get(result.getTargetId());
            if (result.getTargetId() != null && target == null) {
                throw new ApiException(ErrorCode.CONFLICT, "审查结果缺少所属文件，不能冻结",
                        Map.of("resultId", result.getId()));
            }
            ObjectNode snapshot = snapshot(result, task, target,
                    projects.get(task.getProjectId()), issuesByResult.getOrDefault(result.getId(), List.of()),
                    issueFeedback, resultFeedback.get(result.getId()), frozenAt);
            sources.add(new ReportDtos.FrozenSource(index + 1, result.getId(), snapshot));
        }

        AgentReport report = new AgentReport();
        report.setId(AgentIds.next());
        report.setTitle(title);
        report.setState(STATE_INPUT_FROZEN);
        report.setFrozenInput(json(summary(title, sources, frozenAt)));
        report.setRevision(0);
        report.setCreatedAt(frozenAt);
        report.setUpdatedAt(frozenAt);
        db.mapper(AgentReportMapper.class).insert(report);
        for (ReportDtos.FrozenSource source : sources) {
            AgentReportSource row = new AgentReportSource();
            row.setReportId(report.getId());
            row.setResultId(source.resultId());
            row.setSourceSnapshot(json(source.snapshot()));
            row.setOrdinal(source.ordinal());
            db.mapper(AgentReportSourceMapper.class).insert(row);
        }
        return new ReportDtos.FrozenInput(report.getId(), report.getTitle(), report.getState(),
                report.getCreatedAt(), sources.size(), sources);
    }

    public ReportDtos.FrozenInput frozenInput(String reportId) {
        AgentReport report = db.mapper(AgentReportMapper.class)
                .selectById(AgentIds.requireValid(reportId, "报告 ID"));
        if (report == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, "报告不存在：" + reportId);
        }
        List<AgentReportSource> rows = db.mapper(AgentReportSourceMapper.class).selectList(
                new LambdaQueryWrapper<AgentReportSource>()
                        .eq(AgentReportSource::getReportId, report.getId())
                        .orderByAsc(AgentReportSource::getOrdinal));
        List<ReportDtos.FrozenSource> sources = rows.stream()
                .map(row -> new ReportDtos.FrozenSource(row.getOrdinal(), row.getResultId(),
                        readSnapshot(row.getSourceSnapshot())))
                .toList();
        return new ReportDtos.FrozenInput(report.getId(), report.getTitle(), report.getState(),
                report.getCreatedAt(), sources.size(), sources);
    }

    private ObjectNode snapshot(AgentReviewResult result, AgentReviewTask task, AgentTaskTarget target,
                                AgentProject project, List<AgentIssue> issues,
                                Map<String, AgentFeedback> issueFeedback, AgentFeedback resultFeedback,
                                LocalDateTime frozenAt) {
        TaskSource source = source(task);
        ObjectNode node = mapper.createObjectNode();
        node.put("snapshotVersion", SNAPSHOT_VERSION);
        node.put("frozenAt", frozenAt.toString());
        node.put("resultId", result.getId());
        node.put("taskId", result.getTaskId());
        node.put("targetId", result.getTargetId());
        node.put("kind", result.getKind());
        node.put("schemaVersion", result.getSchemaVersion());
        node.put("completeness", result.getCompleteness());
        node.put("resultCreatedAt", result.getCreatedAt() == null ? null : result.getCreatedAt().toString());

        ObjectNode projectNode = node.putObject("project");
        projectNode.put("id", task.getProjectId());
        projectNode.put("name", project == null ? null : project.getName());
        projectNode.put("repoUrl", project == null ? null : project.getRepoUrl());

        ObjectNode taskNode = node.putObject("task");
        taskNode.put("id", task.getId());
        taskNode.put("route", task.getRoute());
        taskNode.put("specialistType", task.getSpecialistType());
        taskNode.put("state", task.getState());
        taskNode.put("sourceKind", source.kind());
        taskNode.put("branch", source.branch());
        taskNode.put("commitSha", source.commitSha());
        taskNode.put("baseSha", source.baseSha());

        ObjectNode targetNode = node.putObject("target");
        targetNode.put("id", target == null ? null : target.getId());
        targetNode.put("path", target == null ? null : target.getPath());
        targetNode.put("oldPath", target == null ? null : target.getOldPath());
        targetNode.put("headSha", target == null ? null : target.getHeadSha());
        targetNode.put("baseSha", target == null ? null : target.getBaseSha());

        ObjectNode resultNode = node.putObject("result");
        resultNode.set("body", bodyNode(result));
        JsonNode provenance = requiredJson(result.getProvenance(), true, "结果来源");
        resultNode.set("provenance", provenance);
        node.set("materials", frozenMaterials(provenance));

        ArrayNode issueNodes = node.putArray("issues");
        for (AgentIssue issue : issues) {
            ObjectNode issueNode = issueNodes.addObject();
            issueNode.put("id", issue.getId());
            issueNode.put("ordinal", issue.getOrdinal());
            issueNode.put("category", issue.getCategory());
            issueNode.put("severity", issue.getSeverity());
            issueNode.put("title", issue.getTitle());
            issueNode.put("reason", issue.getReason());
            issueNode.put("suggestion", issue.getSuggestion());
            issueNode.set("locations", requiredJson(issue.getLocations(), false, "问题位置"));
            issueNode.set("sourceUnits", requiredJson(issue.getSourceUnits(), false, "问题来源单元"));
            FeedbackView feedback = feedback(issueFeedback.get(issue.getId()));
            if (feedback == null) {
                issueNode.putNull("feedback");
            } else {
                issueNode.set("feedback", feedbackNode(feedback));
            }
        }
        FeedbackView resultFeedbackView = feedback(resultFeedback);
        if (resultFeedbackView == null) {
            node.putNull("resultFeedback");
        } else {
            node.set("resultFeedback", feedbackNode(resultFeedbackView));
        }
        return node;
    }

    private ObjectNode summary(String title, List<ReportDtos.FrozenSource> sources, LocalDateTime frozenAt) {
        ObjectNode node = mapper.createObjectNode();
        node.put("version", SNAPSHOT_VERSION);
        node.put("frozenAt", frozenAt.toString());
        node.put("title", title);
        node.put("sourceCount", sources.size());
        ObjectNode kinds = node.putObject("kinds");
        ObjectNode tasks = node.putObject("tasks");
        Set<String> versions = new LinkedHashSet<>();
        ArrayNode versionNodes = node.putArray("versions");
        ArrayNode resultIds = node.putArray("resultIds");
        for (ReportDtos.FrozenSource source : sources) {
            JsonNode snapshot = source.snapshot();
            String kind = snapshot.path("kind").asText();
            kinds.put(kind, kinds.path(kind).asInt(0) + 1);
            String projectId = snapshot.path("project").path("id").asText();
            String taskId = snapshot.path("task").path("id").asText();
            tasks.put(taskId, projectId);
            String headSha = snapshot.path("target").path("headSha").asText("");
            String baseSha = snapshot.path("target").path("baseSha").asText("");
            String versionKey = projectId + "|" + headSha + "|" + baseSha;
            if (versions.add(versionKey)) {
                ObjectNode version = versionNodes.addObject();
                version.put("projectId", projectId);
                version.put("headSha", headSha);
                version.put("baseSha", baseSha);
            }
            resultIds.add(source.resultId());
        }
        return node;
    }

    private Map<String, Integer> issueCounts(List<String> resultIds) {
        if (resultIds.isEmpty()) {
            return Map.of();
        }
        List<AgentIssue> rows = db.mapper(AgentIssueMapper.class).selectList(
                new LambdaQueryWrapper<AgentIssue>()
                        .select(AgentIssue::getResultId)
                        .in(AgentIssue::getResultId, resultIds));
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (AgentIssue row : rows) {
            counts.merge(row.getResultId(), 1, Integer::sum);
        }
        return counts;
    }

    private Map<String, List<AgentIssue>> issuesByResult(List<String> resultIds) {
        List<AgentIssue> rows = db.mapper(AgentIssueMapper.class).selectList(
                new LambdaQueryWrapper<AgentIssue>()
                        .in(AgentIssue::getResultId, resultIds)
                        .orderByAsc(AgentIssue::getOrdinal));
        return rows.stream().collect(Collectors.groupingBy(AgentIssue::getResultId,
                LinkedHashMap::new, Collectors.toList()));
    }

    private Map<String, AgentFeedback> issueFeedback(Map<String, List<AgentIssue>> issuesByResult) {
        List<String> issueIds = issuesByResult.values().stream().flatMap(List::stream)
                .map(AgentIssue::getId).toList();
        return feedback("ISSUE", issueIds);
    }

    private Map<String, AgentFeedback> resultFeedback(List<String> resultIds) {
        return feedback("RESULT", resultIds);
    }

    private Map<String, AgentFeedback> feedback(String kind, List<String> subjectIds) {
        if (subjectIds.isEmpty()) {
            return Map.of();
        }
        return db.mapper(AgentFeedbackMapper.class).selectList(
                new LambdaQueryWrapper<AgentFeedback>()
                        .eq(AgentFeedback::getSubjectKind, kind)
                        .in(AgentFeedback::getSubjectId, subjectIds))
                .stream().collect(Collectors.toMap(AgentFeedback::getSubjectId, Function.identity(), (a, b) -> b));
    }

    private FeedbackView feedback(AgentFeedback row) {
        return row == null ? null : new FeedbackView(row.getAssessment(), row.getComment(),
                row.getRevision() == null ? 0 : row.getRevision(), row.getUpdatedAt());
    }

    private JsonNode feedbackNode(FeedbackView feedback) {
        ObjectNode node = mapper.createObjectNode();
        node.put("assessment", feedback.assessment());
        node.put("comment", feedback.comment());
        node.put("revision", feedback.revision());
        node.put("updatedAt", feedback.updatedAt() == null ? null : feedback.updatedAt().toString());
        return node;
    }

    private JsonNode bodyNode(AgentReviewResult result) {
        String body = result.getBody();
        if (body == null || body.isBlank()) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "保存的结果正文缺失，不能冻结");
        }
        if ("AGENT".equals(result.getKind())) {
            return mapper.getNodeFactory().textNode(body);
        }
        return requiredJson(body, true, "专项结果正文");
    }

    private JsonNode requiredJson(String value, boolean object, String label) {
        JsonNode node = readJsonOrNull(value);
        if (node == null || (object ? !node.isObject() : !node.isArray())) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "保存的" + label + "损坏，不能冻结");
        }
        return node;
    }

    private ArrayNode frozenMaterials(JsonNode provenance) {
        ArrayNode snapshots = mapper.createArrayNode();
        JsonNode references = provenance.path("materials");
        if (references.isMissingNode()) return snapshots;
        if (!references.isArray()) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "保存的材料引用损坏，不能冻结");
        }
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode reference : references) {
            String id = reference.path("id").asText("");
            if (id.isBlank()) {
                throw new ApiException(ErrorCode.INTERNAL_ERROR, "保存的材料引用缺少 ID，不能冻结");
            }
            if (!seen.add(id)) continue;
            AgentMaterial material = db.mapper(AgentMaterialMapper.class).selectById(id);
            if (material == null) {
                throw new ApiException(ErrorCode.CONFLICT, "来源代码材料缺失，不能冻结", Map.of("materialId", id));
            }
            // 从当时已保存的片段复制证据，不向仓库重新读取，也不把元数据引用当成代码快照。
            ObjectNode node = snapshots.addObject();
            node.put("id", material.getId());
            node.put("projectId", material.getProjectId());
            node.put("sha", material.getSha());
            node.put("path", material.getPath());
            node.put("side", material.getSide());
            node.put("startLine", material.getStartLine());
            node.put("endLine", material.getEndLine());
            node.put("content", material.getContent());
            node.put("contentHash", material.getContentHash());
            node.put("completeness", material.getCompleteness());
            node.put("diagnostic", material.getDiagnostic());
        }
        return snapshots;
    }

    private JsonNode readSnapshot(String snapshot) {
        JsonNode node = readJsonOrNull(snapshot);
        if (node == null || !node.isObject()) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "保存的报告来源数据损坏，无法读取");
        }
        return node;
    }

    private JsonNode readJsonOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return mapper.readTree(value);
        } catch (JsonProcessingException ignored) {
            return null;
        }
    }

    private String json(JsonNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "报告冻结数据无法序列化");
        }
    }

    private TaskSource source(AgentReviewTask task) {
        JsonNode input = readJsonOrNull(task.getInputSnapshot());
        JsonNode source = input == null ? null : input.path("source");
        return new TaskSource(source == null || source.isMissingNode() ? "FILES" : source.path("kind").asText("FILES"),
                textOrNull(source, "branch"), textOrNull(source, "commitSha"), textOrNull(source, "baseSha"));
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String requireKind(String kind) {
        String normalized = kind.trim().toUpperCase();
        if (!KINDS.contains(normalized)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "结果类型只能是 ISSUE、PATTERN、COUPLING 或 AGENT");
        }
        return normalized;
    }

    private static <T> Map<String, T> byId(List<String> ids,
                                           Function<List<String>, List<T>> load, Function<T, String> id) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return load.apply(ids).stream().collect(Collectors.toMap(id, Function.identity(), (a, b) -> b));
    }

    private record TaskSource(String kind, String branch, String commitSha, String baseSha) { }

    private record FeedbackView(String assessment, String comment, int revision, LocalDateTime updatedAt) { }
}
