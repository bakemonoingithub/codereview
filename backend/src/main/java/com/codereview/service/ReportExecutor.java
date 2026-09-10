package com.codereview.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.codereview.common.BusinessException;
import com.codereview.common.ResultCode;
import com.codereview.entity.ModelConfig;
import com.codereview.entity.Prompt;
import com.codereview.entity.PromptVersion;
import com.codereview.entity.Report;
import com.codereview.entity.ReportRecord;
import com.codereview.entity.ReviewRecord;
import com.codereview.llm.LlmClient;
import com.codereview.mapper.ModelConfigMapper;
import com.codereview.mapper.PromptMapper;
import com.codereview.mapper.PromptVersionMapper;
import com.codereview.mapper.ReportMapper;
import com.codereview.mapper.ReportRecordMapper;
import com.codereview.mapper.ReviewRecordMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 报告生成编排：聚合多条审查记录结果 + 报告提示词 → LLM → Markdown。
 */
@Slf4j
@Component
public class ReportExecutor {

    private static final String SYSTEM_PROMPT = "你是资深代码审查专家，负责汇总多条审查记录生成一份综合报告，只输出 Markdown，不要输出任何其他文字。";
    private static final String REPORT_TEMPLATE = """
            请根据下面的多条审查记录聚合结果，生成一份代码审查综合报告（Markdown），必须包含以下章节：
            # 概述
            # 问题列表
            # 修复方案
            # 设计模式
            # 模块耦合度
            只输出 Markdown 正文，不要输出任何其他文字。
            """;

    private final ReportMapper reportMapper;
    private final ReportRecordMapper reportRecordMapper;
    private final ReviewRecordMapper reviewRecordMapper;
    private final ModelConfigMapper modelConfigMapper;
    private final PromptMapper promptMapper;
    private final PromptVersionMapper promptVersionMapper;
    private final LlmClient llmClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ReportExecutor(ReportMapper reportMapper, ReportRecordMapper reportRecordMapper,
                          ReviewRecordMapper reviewRecordMapper, ModelConfigMapper modelConfigMapper,
                          PromptMapper promptMapper, PromptVersionMapper promptVersionMapper, LlmClient llmClient) {
        this.reportMapper = reportMapper;
        this.reportRecordMapper = reportRecordMapper;
        this.reviewRecordMapper = reviewRecordMapper;
        this.modelConfigMapper = modelConfigMapper;
        this.promptMapper = promptMapper;
        this.promptVersionMapper = promptVersionMapper;
        this.llmClient = llmClient;
    }

    @Async("reviewTaskExecutor")
    public void execute(Long reportId, Long modelConfigId, Long promptId) {
        Report report = reportMapper.selectById(reportId);
        if (report == null) {
            return;
        }
        report.setStatus(1);
        report.setProgress(0);
        reportMapper.updateById(report);
        try {
            ModelConfig model = modelConfigMapper.selectById(modelConfigId);
            if (model == null) {
                throw new BusinessException(ResultCode.MODEL_NOT_FOUND);
            }
            List<ReviewRecord> records = loadRecords(reportId);
            String prompt = loadPrompt(promptId);
            String aggregation = buildAggregation(records);
            String userPrompt = REPORT_TEMPLATE
                    + (prompt == null || prompt.isBlank() ? "" : "\n补充要求：\n" + prompt)
                    + "\n聚合结果：\n" + aggregation;
            String markdown = llmClient.chat(model.getBaseUrl(), model.getToken(), model.getModelName(),
                    SYSTEM_PROMPT, userPrompt);
            report.setContentMarkdown(markdown);
            report.setStatus(2);
            report.setProgress(100);
            reportMapper.updateById(report);
        } catch (Exception e) {
            log.error("报告生成失败 reportId={}", reportId, e);
            report.setStatus(3);
            report.setProgress(100);
            reportMapper.updateById(report);
        }
    }

    private List<ReviewRecord> loadRecords(Long reportId) {
        List<ReportRecord> links = reportRecordMapper.selectList(
                new LambdaQueryWrapper<ReportRecord>().eq(ReportRecord::getReportId, reportId));
        List<ReviewRecord> records = new ArrayList<>();
        for (ReportRecord link : links) {
            ReviewRecord r = reviewRecordMapper.selectById(link.getRecordId());
            if (r != null) {
                records.add(r);
            }
        }
        return records;
    }

    private String loadPrompt(Long promptId) {
        if (promptId == null) {
            return null;
        }
        Prompt p = promptMapper.selectById(promptId);
        if (p == null || p.getCurrentVersionId() == null) {
            return null;
        }
        PromptVersion v = promptVersionMapper.selectById(p.getCurrentVersionId());
        return v == null ? null : v.getContent();
    }

    private String buildAggregation(List<ReviewRecord> records) {
        StringBuilder sb = new StringBuilder();
        for (ReviewRecord r : records) {
            int type = analyzerType(r);
            sb.append("### 记录 ").append(r.getId()).append("（").append(analyzerName(type)).append("）\n");
            JsonNode result = parseJson(r.getResultJson());
            sb.append("摘要：").append(result.path("summary").asText("")).append("\n");
            sb.append("结果 JSON：").append(r.getResultJson() == null ? "{}" : r.getResultJson()).append("\n\n");
        }
        return sb.toString();
    }

    private int analyzerType(ReviewRecord r) {
        try {
            JsonNode snap = objectMapper.readTree(r.getStrategySnapshotJson());
            return snap.path("analyzerType").asInt(1);
        } catch (Exception e) {
            return 1;
        }
    }

    private String analyzerName(int type) {
        return switch (type) {
            case 2 -> "coupling";
            case 3 -> "design-pattern";
            case 4 -> "api-review";
            default -> "llm-review";
        };
    }

    private JsonNode parseJson(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }
}
