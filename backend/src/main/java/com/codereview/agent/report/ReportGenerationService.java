package com.codereview.agent.report;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.codereview.agent.api.ApiException;
import com.codereview.agent.api.ErrorCode;
import com.codereview.agent.api.PageResponse;
import com.codereview.agent.db.AgentDatabase;
import com.codereview.agent.dto.ReportDtos;
import com.codereview.agent.entity.AgentModelCall;
import com.codereview.agent.entity.AgentModelConfig;
import com.codereview.agent.entity.AgentPrompt;
import com.codereview.agent.entity.AgentPromptVersion;
import com.codereview.agent.entity.AgentReport;
import com.codereview.agent.entity.AgentReportSource;
import com.codereview.agent.mapper.AgentModelCallMapper;
import com.codereview.agent.mapper.AgentModelConfigMapper;
import com.codereview.agent.mapper.AgentPromptMapper;
import com.codereview.agent.mapper.AgentPromptVersionMapper;
import com.codereview.agent.mapper.AgentReportMapper;
import com.codereview.agent.mapper.AgentReportSourceMapper;
import com.codereview.agent.support.AgentIds;
import com.codereview.agent.support.PromptApplicability;
import com.codereview.agent.support.AgentPaging;
import com.codereview.agent.support.AgentTime;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.codereview.agent.report.ReportStates.INPUT_FROZEN;
import static com.codereview.agent.report.ReportStates.QUEUED;

/**
 * 报告读取与生成入口。
 *
 * <p>P32.3 的冻结来源是不可变材料；这里只选择模型和补充要求，把 {@code INPUT_FROZEN}
 * 条件更新为 {@code QUEUED} 后交给后台执行。失败保留冻结来源与错误，不覆盖正文，
 * 也不把程序事实草稿当成完成报告。
 */
@Service
@ConditionalOnProperty(prefix = "agent.datasource", name = "url")
public class ReportGenerationService {

    private final AgentDatabase db;
    private final ObjectMapper mapper;
    private final ReportDispatchService dispatcher;

    public ReportGenerationService(AgentDatabase db, ObjectMapper mapper, ReportDispatchService dispatcher) {
        this.db = db;
        this.mapper = mapper;
        this.dispatcher = dispatcher;
    }

    public ReportDtos.ReportDetail start(String reportId, ReportDtos.GenerateRequest request) {
        String id = AgentIds.requireValid(reportId, "报告 ID");
        AgentReport report = db.mapper(AgentReportMapper.class).selectById(id);
        if (report == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, "报告不存在：" + reportId);
        }
        if (!INPUT_FROZEN.equals(report.getState())) {
            throw new ApiException(ErrorCode.CONFLICT, "报告当前状态不能生成正文",
                    Map.of("state", report.getState()));
        }
        ObjectNode promptSnapshot = promptSnapshot(request.promptId(), request.promptVersionId());
        String modelId = AgentIds.requireValid(request.modelConfigId(), "模型 ID");
        AgentModelConfig model = db.mapper(AgentModelConfigMapper.class).selectById(modelId);
        if (model == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, "模型不存在：" + request.modelConfigId());
        }
        requireUsable(model);

        LocalDateTime now = AgentTime.now();
        String instruction = request.instruction() == null ? null : request.instruction().strip();
        ObjectNode modelSnapshot = modelSnapshot(model);
        ObjectNode instructionSnapshot = instructionSnapshot(instruction, promptSnapshot);
        int changed = db.mapper(AgentReportMapper.class).update(null,
                new LambdaUpdateWrapper<AgentReport>()
                        .eq(AgentReport::getId, id)
                        .eq(AgentReport::getState, INPUT_FROZEN)
                        .eq(AgentReport::getRevision, report.getRevision())
                        .set(AgentReport::getState, QUEUED)
                        .set(AgentReport::getModelSnapshot, json(modelSnapshot))
                        .set(AgentReport::getInstructionSnapshot, json(instructionSnapshot))
                        .set(AgentReport::getBody, null)
                        .set(AgentReport::getErrorCode, null)
                        .set(AgentReport::getErrorMessage, null)
                        .set(AgentReport::getStartedAt, null)
                        .set(AgentReport::getFinishedAt, null)
                        .set(AgentReport::getUpdatedAt, now)
                        .setSql("revision = revision + 1"));
        if (changed == 0) {
            throw new ApiException(ErrorCode.CONFLICT, "报告已被其他操作修改，请刷新后重试");
        }
        dispatcher.dispatch(id);
        return detail(id);
    }

    public PageResponse<ReportDtos.ReportSummary> list(String cursor, Integer limit) {
        return list(null, cursor, limit, null, null);
    }

    public PageResponse<ReportDtos.ReportSummary> list(String cursor, Integer limit, Integer pageNum, Integer pageSize) {
        return list(null, cursor, limit, pageNum, pageSize);
    }

    public PageResponse<ReportDtos.ReportSummary> list(String q, String cursor, Integer limit,
                                                        Integer pageNum, Integer pageSize) {
        return list(q, null, cursor, limit, pageNum, pageSize);
    }

    public PageResponse<ReportDtos.ReportSummary> list(String q, String state, String cursor, Integer limit,
                                                        Integer pageNum, Integer pageSize) {
        LambdaQueryWrapper<AgentReport> wrapper = new LambdaQueryWrapper<>();
        if (q != null && !q.isBlank()) {
            String keyword = q.strip();
            wrapper.and(w -> w.like(AgentReport::getTitle, keyword)
                    .or().like(AgentReport::getId, keyword));
        }
        if (state != null && !state.isBlank()) {
            wrapper.eq(AgentReport::getState, state.strip());
        }
        PageResponse<AgentReport> page = AgentPaging.query(
                db.mapper(AgentReportMapper.class), wrapper, cursor, limit, pageNum, pageSize,
                AgentReport::getCreatedAt, AgentReport::getId);
        return page.withItems(page.items().stream().map(this::summary).toList());
    }

    public void delete(String id, int expectedRevision) {
        new com.codereview.agent.service.RecordDeletionService(db).report(id, expectedRevision);
    }

    public ReportDtos.ReportDetail detail(String reportId) {
        AgentReport report = require(reportId);
        JsonNode frozen = readObject(report.getFrozenInput());
        List<ReportDtos.SourceSummary> sources = sourceSummaries(report, frozen);
        List<ReportDtos.CallSummary> calls = calls(report.getId());
        ReportDtos.ModelView model = modelView(report.getModelSnapshot());
        return new ReportDtos.ReportDetail(report.getId(), report.getTitle(), report.getState(),
                report.getBody(), report.getErrorCode(), report.getErrorMessage(), model,
                promptView(report.getInstructionSnapshot()),
                instruction(report.getInstructionSnapshot()), sourceCount(frozen, sources.size()),
                taskCount(frozen), versionCount(frozen), kinds(frozen), sources, calls,
                report.getCreatedAt(), report.getUpdatedAt(), report.getStartedAt(),
                report.getFinishedAt());
    }

    public ReportDtos.FrozenSource source(String reportId, int ordinal) {
        AgentReport report = require(reportId);
        AgentReportSource row = db.mapper(AgentReportSourceMapper.class).selectOne(
                new LambdaQueryWrapper<AgentReportSource>()
                        .eq(AgentReportSource::getReportId, report.getId())
                        .eq(AgentReportSource::getOrdinal, ordinal));
        if (row == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, "报告来源不存在：" + ordinal);
        }
        JsonNode snapshot = readObject(row.getSourceSnapshot());
        if (snapshot == null) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "保存的报告来源数据损坏，无法读取");
        }
        String taskId = snapshot.path("taskId").asText(snapshot.path("task").path("id").asText(""));
        boolean available = !taskId.isBlank() && db.mapper(com.codereview.agent.mapper.AgentReviewTaskMapper.class)
                .selectById(taskId) != null;
        return new ReportDtos.FrozenSource(row.getOrdinal(), row.getResultId(), snapshot, available);
    }

    public List<ReportDtos.CallSummary> calls(String reportId) {
        require(reportId);
        String id = AgentIds.requireValid(reportId, "报告 ID");
        return db.mapper(AgentModelCallMapper.class).selectList(
                        new LambdaQueryWrapper<AgentModelCall>()
                                .eq(AgentModelCall::getReportId, id)
                                .orderByAsc(AgentModelCall::getCreatedAt)
                                .orderByAsc(AgentModelCall::getId))
                .stream().map(call -> new ReportDtos.CallSummary(call.getId(), call.getPurpose(),
                        call.getAttempt() == null ? 1 : call.getAttempt(), call.getState(),
                        call.getErrorCode(), call.getErrorMessage(), call.getCreatedAt(),
                        call.getStartedAt(), call.getFinishedAt()))
                .toList();
    }

    public Download download(String reportId) {
        AgentReport report = require(reportId);
        if (!ReportStates.SUCCEEDED.equals(report.getState()) || report.getBody() == null
                || report.getBody().isBlank()) {
            throw new ApiException(ErrorCode.CONFLICT, "报告尚未成功生成，不能下载正文",
                    Map.of("state", report.getState()));
        }
        return new Download(fileName(report.getTitle()), report.getBody().getBytes(StandardCharsets.UTF_8));
    }

    private AgentReport require(String reportId) {
        AgentReport report = db.mapper(AgentReportMapper.class)
                .selectById(AgentIds.requireValid(reportId, "报告 ID"));
        if (report == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, "报告不存在：" + reportId);
        }
        return report;
    }

    private ReportDtos.ReportSummary summary(AgentReport report) {
        JsonNode frozen = readObject(report.getFrozenInput());
        ReportDtos.ModelView model = modelView(report.getModelSnapshot());
        return new ReportDtos.ReportSummary(report.getId(), report.getTitle(), report.getState(),
                report.getErrorCode(), report.getErrorMessage(), model == null ? null : model.name(),
                sourceCount(frozen, 0), taskCount(frozen), versionCount(frozen), kinds(frozen),
                report.getCreatedAt(), report.getUpdatedAt(), report.getStartedAt(),
                report.getFinishedAt(), report.getRevision());
    }

    private List<ReportDtos.SourceSummary> sourceSummaries(AgentReport report, JsonNode frozen) {
        List<ReportDtos.SourceSummary> result = new ArrayList<>();
        JsonNode saved = frozen == null ? null : frozen.path("sources");
        if (saved != null && saved.isArray()) {
            for (JsonNode node : saved) {
                result.add(new ReportDtos.SourceSummary(node.path("ordinal").asInt(), text(node, "resultId"),
                        text(node, "kind"), text(node, "projectId"), text(node, "projectName"),
                        text(node, "taskId"), text(node, "taskRoute"), text(node, "path"),
                        text(node, "headSha"), text(node, "baseSha"), text(node, "summary"),
                        node.path("issueCount").asInt(), node.path("accurate").asInt(),
                        node.path("inaccurate").asInt(), node.path("unknown").asInt(),
                        node.path("unassessed").asInt()));
            }
            return result;
        }
        // 兼容 P32.3 早期记录：摘要没有 sources 时按来源逐条读取最小字段；不联查最新结果。
        for (AgentReportSource row : db.mapper(AgentReportSourceMapper.class).selectList(
                new LambdaQueryWrapper<AgentReportSource>()
                        .eq(AgentReportSource::getReportId, report.getId())
                        .orderByAsc(AgentReportSource::getOrdinal))) {
            JsonNode snapshot = readObject(row.getSourceSnapshot());
            if (snapshot == null) {
                continue;
            }
            JsonNode target = snapshot.path("target");
            JsonNode project = snapshot.path("project");
            JsonNode task = snapshot.path("task");
            result.add(new ReportDtos.SourceSummary(row.getOrdinal(), row.getResultId(),
                    text(snapshot, "kind"), text(project, "id"), text(project, "name"),
                    text(snapshot, "taskId"), text(task, "route"), text(target, "path"),
                    text(target, "headSha"), text(target, "baseSha"),
                    text(snapshot.path("result").path("body"), "summary"),
                    snapshot.path("issues").isArray() ? snapshot.path("issues").size() : 0,
                    0, 0, 0, 0));
        }
        return result;
    }

    private static void requireUsable(AgentModelConfig model) {
        if (model.getBaseUrl() == null || model.getBaseUrl().isBlank()) {
            throw new ApiException(ErrorCode.CONFIG_INCOMPLETE, "模型接口地址为空",
                    Map.of("modelId", model.getId()));
        }
        if (model.getModelName() == null || model.getModelName().isBlank()) {
            throw new ApiException(ErrorCode.CONFIG_INCOMPLETE, "模型名称为空",
                    Map.of("modelId", model.getId()));
        }
        if (model.getContextWindow() == null || model.getContextWindow() <= 0) {
            throw new ApiException(ErrorCode.CONFIG_INCOMPLETE,
                    "模型未声明上下文窗口，无法判断报告容量", Map.of("modelId", model.getId()));
        }
        if (model.getProtocol() != null && !"OPENAI_CHAT".equals(model.getProtocol())) {
            throw new ApiException(ErrorCode.CONFIG_INCOMPLETE,
                    "暂不支持该模型协议：" + model.getProtocol(), Map.of("modelId", model.getId()));
        }
    }

    private ObjectNode modelSnapshot(AgentModelConfig model) {
        ObjectNode node = mapper.createObjectNode();
        node.put("id", model.getId());
        node.put("name", model.getName());
        node.put("baseUrl", model.getBaseUrl());
        node.put("modelName", model.getModelName());
        node.put("contextWindow", model.getContextWindow());
        node.put("maxOutput", model.getMaxOutput());
        node.put("tokenizer", model.getTokenizer());
        node.put("protocol", model.getProtocol() == null ? "OPENAI_CHAT" : model.getProtocol());
        node.put("revision", model.getRevision());
        return node;
    }

    private ObjectNode instructionSnapshot(String instruction, ObjectNode promptSnapshot) {
        ObjectNode node = mapper.createObjectNode();
        node.put("version", ReportFactsBuilder.FACTS_VERSION);
        node.put("text", instruction);
        if (promptSnapshot == null) {
            node.putNull("prompt");
        } else {
            node.set("prompt", promptSnapshot);
        }
        return node;
    }

    private ObjectNode promptSnapshot(String promptId, String promptVersionId) {
        if (promptId == null && promptVersionId == null) {
            return null;
        }
        if (promptId == null || promptVersionId == null || promptId.isBlank() || promptVersionId.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "报告通用规则和具体版本必须同时选择");
        }
        AgentIds.requireValid(promptId, "提示词 ID");
        AgentIds.requireValid(promptVersionId, "提示词版本 ID");
        AgentPrompt prompt = db.mapper(AgentPromptMapper.class).selectById(promptId);
        if (prompt == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, "报告通用规则不存在：" + promptId);
        }
        if (!PromptApplicability.supports(prompt.getPurpose(), "REPORT")) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "所选提示词须为报告生成或通用用途");
        }
        AgentPromptVersion version = db.mapper(AgentPromptVersionMapper.class).selectById(promptVersionId);
        if (version == null || !promptId.equals(version.getPromptId())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "所选提示词版本不属于该报告通用规则");
        }
        if (version.getContent() == null || version.getContent().isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "所选报告通用规则版本正文不能为空");
        }
        ObjectNode snapshot = mapper.createObjectNode();
        snapshot.put("id", prompt.getId());
        snapshot.put("name", prompt.getName());
        snapshot.put("versionId", version.getId());
        snapshot.put("versionNo", version.getVersionNo());
        snapshot.put("content", version.getContent());
        return snapshot;
    }

    private ReportDtos.PromptView promptView(String instructionSnapshot) {
        JsonNode snapshot = readObject(instructionSnapshot);
        JsonNode prompt = snapshot == null ? null : snapshot.get("prompt");
        if (prompt == null || prompt.isNull()) {
            return null;
        }
        if (!prompt.isObject() || text(prompt, "id") == null || text(prompt, "name") == null
                || text(prompt, "versionId") == null || text(prompt, "content") == null
                || !prompt.path("versionNo").canConvertToInt()) {
            throw new ApiException(ErrorCode.REPORT_INPUT_INVALID, "报告通用规则快照不完整");
        }
        return new ReportDtos.PromptView(text(prompt, "id"), text(prompt, "name"),
                text(prompt, "versionId"), prompt.path("versionNo").asInt(), text(prompt, "content"));
    }

    private ReportDtos.ModelView modelView(String snapshot) {
        JsonNode node = readObject(snapshot);
        if (node == null) {
            return null;
        }
        return new ReportDtos.ModelView(text(node, "id"), text(node, "name"), text(node, "modelName"),
                intOrNull(node, "contextWindow"), intOrNull(node, "maxOutput"),
                text(node, "tokenizer"), text(node, "protocol"), intOrNull(node, "revision"));
    }

    private String instruction(String snapshot) {
        JsonNode node = readObject(snapshot);
        return node == null ? null : text(node, "text");
    }

    private Map<String, Integer> kinds(JsonNode frozen) {
        Map<String, Integer> kinds = new LinkedHashMap<>();
        JsonNode node = frozen == null ? null : frozen.path("kinds");
        if (node != null && node.isObject()) {
            node.fields().forEachRemaining(entry -> kinds.put(entry.getKey(), entry.getValue().asInt()));
        }
        return kinds;
    }

    private int sourceCount(JsonNode frozen, int fallback) {
        JsonNode node = frozen == null ? null : frozen.get("sourceCount");
        return node != null && node.isInt() ? node.asInt() : fallback;
    }

    private int taskCount(JsonNode frozen) {
        JsonNode tasks = frozen == null ? null : frozen.path("tasks");
        return tasks != null && tasks.isObject() ? tasks.size() : 0;
    }

    private int versionCount(JsonNode frozen) {
        JsonNode versions = frozen == null ? null : frozen.path("versions");
        return versions != null && versions.isArray() ? versions.size() : 0;
    }

    private JsonNode readObject(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            JsonNode node = mapper.readTree(value);
            return node != null && node.isObject() ? node : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String json(JsonNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "报告快照无法序列化");
        }
    }

    private static Integer intOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asInt();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String fileName(String title) {
        String name = title == null || title.isBlank() ? "审查报告" : title.strip();
        return name.replaceAll("[\\\\/:*?\"<>|\\r\\n]", "_") + ".md";
    }

    /** 下载正文与阅读正文是同一份保存内容。 */
    public record Download(String fileName, byte[] content) {
    }
}
