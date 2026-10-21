package com.codereview.agent.report;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.codereview.agent.api.ApiException;
import com.codereview.agent.api.ErrorCode;
import com.codereview.agent.db.AgentDatabase;
import com.codereview.agent.dto.ReportDtos;
import com.codereview.agent.entity.AgentModelConfig;
import com.codereview.agent.entity.AgentReport;
import com.codereview.agent.entity.AgentReportSource;
import com.codereview.agent.invocation.InvocationRequest;
import com.codereview.agent.invocation.InvocationResult;
import com.codereview.agent.invocation.InvocationScope;
import com.codereview.agent.invocation.ModelInvocationService;
import com.codereview.agent.invocation.ModelSnapshot;
import com.codereview.agent.mapper.AgentModelConfigMapper;
import com.codereview.agent.mapper.AgentReportMapper;
import com.codereview.agent.mapper.AgentReportSourceMapper;
import com.codereview.agent.prompt.AssembledPrompt;
import com.codereview.agent.prompt.PromptAssemblyService;
import com.codereview.agent.prompt.PromptInput;
import com.codereview.agent.prompt.PromptPurpose;
import com.codereview.agent.support.AgentTime;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static com.codereview.agent.report.ReportStates.QUEUED;
import static com.codereview.agent.report.ReportStates.RUNNING;
import static com.codereview.agent.report.ReportStates.SUCCEEDED;

/**
 * 报告生成的后台执行器。
 *
 * <p>先条件更新为 RUNNING，再读取冻结来源、整理事实、经统一组装与公共调用生成正文。
 * 成功要求 Markdown 正文至少引用一个存在的冻结来源；失败只写入错误，不写正文，
 * 冻结来源和输入摘要保持不变。报告调用与专项调用共用容量、并发和调用记录。
 */
@Service
@ConditionalOnProperty(prefix = "agent.datasource", name = "url")
public class ReportExecutionWorker {

    private static final Logger log = LoggerFactory.getLogger(ReportExecutionWorker.class);

    private final AgentDatabase db;
    private final ObjectMapper mapper;
    private final ReportFactsBuilder factsBuilder;
    private final PromptAssemblyService assembly;
    private final ModelInvocationService invocation;
    private final ReportGenerationProperties properties;

    public ReportExecutionWorker(AgentDatabase db, ObjectMapper mapper,
                                 ReportFactsBuilder factsBuilder, PromptAssemblyService assembly,
                                 ModelInvocationService invocation,
                                 ReportGenerationProperties properties) {
        this.db = db;
        this.mapper = mapper;
        this.factsBuilder = factsBuilder;
        this.assembly = assembly;
        this.invocation = invocation;
        this.properties = properties;
    }

    public void execute(String reportId) {
        AgentReport report = db.mapper(AgentReportMapper.class).selectById(reportId);
        if (report == null || !QUEUED.equals(report.getState())) {
            return;
        }
        if (!markRunning(report)) {
            return;
        }
        try {
            List<AgentReportSource> sources = db.mapper(AgentReportSourceMapper.class).selectList(
                    new LambdaQueryWrapper<AgentReportSource>()
                            .eq(AgentReportSource::getReportId, reportId)
                            .orderByAsc(AgentReportSource::getOrdinal));
            ReportFactsBuilder.ReportFacts facts = factsBuilder.build(sources);
            ModelSnapshot model = model(report);
            AssembledPrompt prompt = assembly.assemble(new PromptInput(PromptPurpose.REPORT, "1",
                    null, null, null, null, null, null, null, List.of(), List.of(),
                    facts.missingNotes(), facts.facts(), null, report.getTitle(),
                    instruction(report)));
            LocalDateTime deadline = AgentTime.now()
                    .plusMinutes(Math.max(1, properties.getGenerationTimeoutMinutes()));
            InvocationResult result = invocation.invoke(new InvocationRequest(model, prompt,
                    InvocationScope.standalone(deadline), false, reportId,
                    properties.getOutputTokens()));
            String body = result.content();
            if (body != null && body.length() > Math.max(1, properties.getMaxBodyChars())) {
                throw new ApiException(ErrorCode.MODEL_OUTPUT_INVALID,
                        "报告正文超过保存上限 " + properties.getMaxBodyChars() + " 字符",
                        Map.of("length", body.length()));
            }
            ReportCitationValidator.validate(body, facts.sourceCount());
            finishSuccess(reportId, body);
        } catch (ApiException e) {
            log.warn("报告 {} 生成失败：{} {}", reportId, e.errorCode().code(), e.getMessage());
            finishFailure(reportId, e.errorCode().code(), e.getMessage());
        } catch (RuntimeException e) {
            log.error("报告 {} 生成异常", reportId, e);
            finishFailure(reportId, ErrorCode.INTERNAL_ERROR.code(), "报告生成异常");
        }
    }

    private boolean markRunning(AgentReport report) {
        int changed = db.mapper(AgentReportMapper.class).update(null,
                new LambdaUpdateWrapper<AgentReport>()
                        .eq(AgentReport::getId, report.getId())
                        .eq(AgentReport::getState, QUEUED)
                        .eq(AgentReport::getRevision, report.getRevision())
                        .set(AgentReport::getState, RUNNING)
                        .set(AgentReport::getStartedAt, AgentTime.now())
                        .set(AgentReport::getUpdatedAt, AgentTime.now())
                        .setSql("revision = revision + 1"));
        return changed > 0;
    }

    private void finishSuccess(String reportId, String body) {
        int changed = db.mapper(AgentReportMapper.class).update(null,
                new LambdaUpdateWrapper<AgentReport>()
                        .eq(AgentReport::getId, reportId)
                        .eq(AgentReport::getState, RUNNING)
                        .set(AgentReport::getState, SUCCEEDED)
                        .set(AgentReport::getBody, body)
                        .set(AgentReport::getErrorCode, null)
                        .set(AgentReport::getErrorMessage, null)
                        .set(AgentReport::getFinishedAt, AgentTime.now())
                        .set(AgentReport::getUpdatedAt, AgentTime.now())
                        .setSql("revision = revision + 1"));
        if (changed == 0) {
            log.info("报告 {} 的成功结果未写入：报告已被其他操作结束", reportId);
        }
    }

    private void finishFailure(String reportId, String code, String message) {
        int changed = db.mapper(AgentReportMapper.class).update(null,
                new LambdaUpdateWrapper<AgentReport>()
                        .eq(AgentReport::getId, reportId)
                        .eq(AgentReport::getState, RUNNING)
                        .set(AgentReport::getState, ReportStates.FAILED)
                        .set(AgentReport::getBody, null)
                        .set(AgentReport::getErrorCode, truncate(code, 40))
                        .set(AgentReport::getErrorMessage, truncate(message, 1000))
                        .set(AgentReport::getFinishedAt, AgentTime.now())
                        .set(AgentReport::getUpdatedAt, AgentTime.now())
                        .setSql("revision = revision + 1"));
        if (changed == 0) {
            log.info("报告 {} 的失败结果未写入：报告已被其他操作结束", reportId);
        }
    }

    private ModelSnapshot model(AgentReport report) {
        JsonNode snapshot = readObject(report.getModelSnapshot());
        if (snapshot == null) {
            throw new ApiException(ErrorCode.REPORT_INPUT_INVALID, "报告缺少模型快照，不能生成");
        }
        String modelId = text(snapshot, "id");
        AgentModelConfig current = modelId == null ? null
                : db.mapper(AgentModelConfigMapper.class).selectById(modelId);
        if (current == null) {
            throw new ApiException(ErrorCode.CONFIG_INCOMPLETE, "生成模型已被删除，不能继续生成报告",
                    Map.of("modelId", modelId == null ? "" : modelId));
        }
        return new ModelSnapshot(modelId, text(snapshot, "name"), text(snapshot, "baseUrl"),
                text(snapshot, "modelName"), current.getCredential(), intOrNull(snapshot, "contextWindow"),
                intOrNull(snapshot, "maxOutput"), text(snapshot, "tokenizer"),
                text(snapshot, "protocol"), intOrNull(snapshot, "revision"));
    }

    private String instruction(AgentReport report) {
        JsonNode snapshot = readObject(report.getInstructionSnapshot());
        return snapshot == null ? null : text(snapshot, "text");
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

    private static Integer intOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asInt();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
