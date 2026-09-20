package com.codereview.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.codereview.common.BusinessException;
import com.codereview.common.ResultCode;
import com.codereview.config.ReportProperties;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 报告生成编排：聚合多条审查记录结果 + 报告提示词 → LLM → Markdown。
 *
 * <p>两条与性能/稳定性相关的约定：
 * <ul>
 *   <li><b>取数一次批量、只取要用的列</b>：原先按 link 逐条 {@code selectById}（N+1），
 *       每次都把整行（含 MEDIUMTEXT 的 {@code result_json}）拉回来；现在按 id 分批
 *       {@code in} 查询 + 列投影，并把结果按 link 顺序重排、按 id 去重。</li>
 *   <li><b>提示词有预算</b>：聚合文本由 {@link ReportAggregationBuilder} 按
 *       {@link ReportProperties#getPromptMaxChars()} 裁剪，最终 user prompt 只会更短 ——
 *       超出预算时提示词里会写明裁剪了多少条，而不是硬发给网关后在那里失败。</li>
 * </ul>
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
            其中「模块耦合度」一节请按**模块/包**维度陈述（数据在耦合度记录的 moduleSummary / modules /
            moduleEdges / moduleCycles 字段里）：指出跨模块依赖、模块级循环依赖、以及最不稳定（Ce 大、I 高）的模块。
            只输出 Markdown 正文，不要输出任何其他文字。
            """;

    /** 单次 in 查询的 id 上限：防"勾选了很多条"时 IN 列表过长、单次结果集过大。 */
    private static final int ID_BATCH = 500;
    /** 模板与补充要求把预算挤到很小时，至少给聚合留这么多字符，避免"预算形同虚设"。 */
    private static final int MIN_AGGREGATION_BUDGET = 2_000;

    private final ReportMapper reportMapper;
    private final ReportRecordMapper reportRecordMapper;
    private final ReviewRecordMapper reviewRecordMapper;
    private final ModelConfigMapper modelConfigMapper;
    private final PromptMapper promptMapper;
    private final PromptVersionMapper promptVersionMapper;
    private final LlmClient llmClient;
    private final ReportProperties reportProperties;
    private final ReportAggregationBuilder aggregationBuilder;

    public ReportExecutor(ReportMapper reportMapper, ReportRecordMapper reportRecordMapper,
                          ReviewRecordMapper reviewRecordMapper, ModelConfigMapper modelConfigMapper,
                          PromptMapper promptMapper, PromptVersionMapper promptVersionMapper, LlmClient llmClient,
                          ReportProperties reportProperties, ReportAggregationBuilder aggregationBuilder) {
        this.reportMapper = reportMapper;
        this.reportRecordMapper = reportRecordMapper;
        this.reviewRecordMapper = reviewRecordMapper;
        this.modelConfigMapper = modelConfigMapper;
        this.promptMapper = promptMapper;
        this.promptVersionMapper = promptVersionMapper;
        this.llmClient = llmClient;
        this.reportProperties = reportProperties;
        this.aggregationBuilder = aggregationBuilder;
    }

    @Async("reviewTaskExecutor")
    public void execute(Long reportId, Long modelConfigId, Long promptId) {
        Report report = reportMapper.selectById(reportId);
        if (report == null) {
            return;
        }
        report.setStatus(1);
        report.setProgress(0);
        // 耗时从"任务真正开始"算起（排队时间不计），用于自证指标 8 的完整耗时
        report.setStartedAt(LocalDateTime.now());
        reportMapper.updateById(report);
        try {
            ModelConfig model = modelConfigMapper.selectById(modelConfigId);
            if (model == null) {
                throw new BusinessException(ResultCode.MODEL_NOT_FOUND);
            }
            List<ReviewRecord> records = loadRecords(reportId);
            String prompt = loadPrompt(promptId);
            String requirement = (prompt == null || prompt.isBlank()) ? "" : "\n补充要求：\n" + prompt;
            int totalBudget = reportProperties.getPromptMaxChars();
            int aggregationBudget = totalBudget - REPORT_TEMPLATE.length() - requirement.length();
            if (aggregationBudget < MIN_AGGREGATION_BUDGET) {
                log.warn("报告 {} 的模板与补充要求已占用 {} 字符（预算 {}），聚合额度仅剩 {}",
                        reportId, REPORT_TEMPLATE.length() + requirement.length(), totalBudget,
                        Math.max(0, aggregationBudget));
            }
            ReportAggregationBuilder.Result aggregation =
                    aggregationBuilder.build(records, Math.max(0, aggregationBudget));
            String userPrompt = truncateToBudget(
                    REPORT_TEMPLATE + requirement + "\n聚合结果：\n" + aggregation.text(), totalBudget, reportId);
            String markdown = llmClient.chat(model.getBaseUrl(), model.getToken(), model.getModelName(),
                    SYSTEM_PROMPT, userPrompt);
            report.setContentMarkdown(markdown);
            report.setStatus(2);
            report.setProgress(100);
            report.setFinishedAt(LocalDateTime.now());
            reportMapper.updateById(report);
        } catch (Exception e) {
            log.error("报告生成失败 reportId={}", reportId, e);
            report.setStatus(3);
            report.setProgress(100);
            // 失败也要写结束时间，否则"失败的报告耗时多久"无从得知
            report.setFinishedAt(LocalDateTime.now());
            reportMapper.updateById(report);
        }
    }

    /** 最后一道保险：无论聚合怎么裁，发给网关的 user prompt 都不超过预算。 */
    private String truncateToBudget(String prompt, int budget, Long reportId) {
        if (prompt.length() <= budget) {
            return prompt;
        }
        log.warn("报告 {} 的 user prompt {} 字符超过预算 {}，已硬截断", reportId, prompt.length(), budget);
        return prompt.substring(0, Math.max(0, budget));
    }

    /**
     * 取本报告勾选的审查记录。
     *
     * <p>原先对每条 link 调一次 {@code selectById}（N+1，每条都把整行含 {@code result_json} 拉回内存）。
     * 现在：id 先去重（前端传重复 id 时 link 表会有两行），按 {@link #ID_BATCH} 分批 {@code in} 查询，
     * 只 select 聚合真正要用的三列；返回顺序仍按 **link 的顺序**（数据库的 IN 查询不保证顺序）。
     */
    private List<ReviewRecord> loadRecords(Long reportId) {
        List<ReportRecord> links = reportRecordMapper.selectList(
                new LambdaQueryWrapper<ReportRecord>().eq(ReportRecord::getReportId, reportId));
        if (links == null || links.isEmpty()) {
            return List.of();
        }
        List<Long> ids = links.stream()
                .map(ReportRecord::getRecordId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, ReviewRecord> byId = new HashMap<>();
        for (int i = 0; i < ids.size(); i += ID_BATCH) {
            List<Long> batch = ids.subList(i, Math.min(ids.size(), i + ID_BATCH));
            // 用**字符串列名**做投影，而不是 lambda：lambda 版 select(...) 会立刻解析实体列
            // （需要 MyBatis-Plus 的 TableInfo 缓存），纯单测（没有 Spring 上下文）下会抛
            // "can not find lambda cache for this entity"。列名与 ReviewRecord 的三列一一对应，
            // 并由 ReportExecutorTest 断言"投影里没有用不到的列"。
            List<ReviewRecord> found = reviewRecordMapper.selectList(new QueryWrapper<ReviewRecord>()
                    .select("id", "strategy_snapshot_json", "result_json")
                    .in("id", batch));
            if (found == null) {
                continue;
            }
            for (ReviewRecord r : found) {
                byId.put(r.getId(), r);
            }
        }
        List<ReviewRecord> ordered = new ArrayList<>(ids.size());
        for (Long id : ids) {
            ReviewRecord r = byId.get(id);
            if (r != null) {
                ordered.add(r);
            }
        }
        return ordered;
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
}
