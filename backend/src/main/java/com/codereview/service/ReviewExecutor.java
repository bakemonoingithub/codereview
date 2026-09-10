package com.codereview.service;

import com.codereview.analyzer.AnalysisContext;
import com.codereview.analyzer.AnalyzeOutcome;
import com.codereview.analyzer.Analyzer;
import com.codereview.entity.Project;
import com.codereview.entity.ReviewRecord;
import com.codereview.git.GitRepoRef;
import com.codereview.mapper.ProjectMapper;
import com.codereview.mapper.ReviewRecordMapper;
import com.codereview.review.ReviewStatus;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 审查编排（M3）：按策略分析器类型分发到对应 Analyzer，负责记录生命周期（执行中/成功/失败）。
 */
@Slf4j
@Component
public class ReviewExecutor {

    private final ReviewRecordMapper reviewRecordMapper;
    private final ProjectMapper projectMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<Integer, Analyzer> analyzers;

    public ReviewExecutor(ReviewRecordMapper reviewRecordMapper, ProjectMapper projectMapper,
                          List<Analyzer> analyzers) {
        this.reviewRecordMapper = reviewRecordMapper;
        this.projectMapper = projectMapper;
        this.analyzers = analyzers.stream().collect(Collectors.toMap(Analyzer::type, Function.identity()));
    }

    @Async("reviewTaskExecutor")
    public void execute(Long recordId) {
        ReviewRecord record = reviewRecordMapper.selectById(recordId);
        if (record == null) {
            return;
        }
        markRunning(record);
        try {
            AnalyzeOutcome outcome = analyzer(record).analyze(buildContext(record));
            persist(record, outcome);
        } catch (Exception e) {
            log.error("审查执行失败 recordId={}", recordId, e);
            markFailed(record);
        }
    }

    @Async("reviewTaskExecutor")
    public void retry(Long recordId) {
        ReviewRecord record = reviewRecordMapper.selectById(recordId);
        if (record == null) {
            return;
        }
        try {
            JsonNode oldResult = record.getResultJson() == null || record.getResultJson().isBlank()
                    ? null : objectMapper.readTree(record.getResultJson());
            markRunning(record);
            AnalyzeOutcome outcome = analyzer(record).retry(buildContext(record), oldResult);
            persist(record, outcome);
        } catch (Exception e) {
            log.error("重审失败 recordId={}", recordId, e);
            markFailed(record);
        }
    }

    private Analyzer analyzer(ReviewRecord record) throws Exception {
        JsonNode snap = objectMapper.readTree(record.getStrategySnapshotJson());
        int type = snap.path("analyzerType").asInt(1);
        Analyzer a = analyzers.get(type);
        if (a == null) {
            throw new IllegalStateException("不支持的分析器类型: " + type);
        }
        return a;
    }

    private AnalysisContext buildContext(ReviewRecord record) throws Exception {
        Project project = projectMapper.selectById(record.getProjectId());
        GitRepoRef ref = GitRepoRef.parse(project.getGiteaUrl());
        JsonNode snap = objectMapper.readTree(record.getStrategySnapshotJson());
        JsonNode model = snap.path("model");
        List<String> scope = objectMapper.readValue(
                record.getScopeJson() == null || record.getScopeJson().isBlank() ? "[]" : record.getScopeJson(),
                new TypeReference<List<String>>() {
                });
        return new AnalysisContext(record, project, ref, scope,
                model.path("baseUrl").asText(),
                model.path("apiKey").asText(),
                model.path("modelName").asText(),
                snap.path("params"),
                snap.path("customPrompt").asText(null),
                snap.path("mergeFiles").asBoolean(false),
                pct -> {
                    record.setProgress(pct);
                    reviewRecordMapper.updateById(record);
                });
    }

    private void persist(ReviewRecord record, AnalyzeOutcome outcome) {
        record.setResultJson(outcome.result().toString());
        record.setStatus(outcome.status());
        record.setProgress(100);
        record.setFinishedAt(LocalDateTime.now());
        reviewRecordMapper.updateById(record);
    }

    private void markRunning(ReviewRecord record) {
        record.setStatus(ReviewStatus.RUNNING);
        if (record.getStartedAt() == null) {
            record.setStartedAt(LocalDateTime.now());
        }
        record.setProgress(0);
        reviewRecordMapper.updateById(record);
    }

    private void markFailed(ReviewRecord record) {
        record.setStatus(ReviewStatus.FAILED);
        record.setProgress(100);
        record.setFinishedAt(LocalDateTime.now());
        reviewRecordMapper.updateById(record);
    }
}
