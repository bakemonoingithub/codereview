package com.codereview.service;

import com.codereview.analyzer.AnalysisContext;
import com.codereview.analyzer.AnalyzeOutcome;
import com.codereview.analyzer.Analyzer;
import com.codereview.config.ReviewProperties;
import com.codereview.entity.Project;
import com.codereview.entity.ReviewRecord;
import com.codereview.mapper.ProjectMapper;
import com.codereview.mapper.ReviewRecordMapper;
import com.codereview.review.ReviewStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 编排层把**任务级 deadline** 交给分析器（backlog ⑥「整个审查任务没有总超时」）。
 *
 * <p>deadline 从 {@code started_at} 算起（排队时间不计）：一条已经跑了 40 分钟的记录，
 * 重审后不该再白拿满 50 分钟。缺失 {@code started_at} 时退回当前时刻。
 *
 * <p>断言的是**下传给分析器的 context**，与"分析器怎么用它"解耦；
 * 后者由 {@code LlmReviewAnalyzerDeadlineTest} / {@code DiffReviewAnalyzerTest} 覆盖。
 */
class ReviewExecutorDeadlineTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ReviewRecordMapper recordMapper;
    private ProjectMapper projectMapper;
    private Analyzer analyzer;
    private ReviewProperties props;
    private ReviewExecutor executor;

    @BeforeEach
    void setUp() throws Exception {
        recordMapper = mock(ReviewRecordMapper.class);
        projectMapper = mock(ProjectMapper.class);
        analyzer = mock(Analyzer.class);
        props = new ReviewProperties();
        when(analyzer.type()).thenReturn(1);
        executor = new ReviewExecutor(recordMapper, projectMapper, List.of(analyzer), props);
        when(projectMapper.selectById(9L)).thenReturn(project());
        when(analyzer.analyze(any())).thenReturn(outcome());
    }

    private ReviewRecord record(LocalDateTime startedAt) {
        ReviewRecord r = new ReviewRecord();
        r.setId(1L);
        r.setProjectId(9L);
        r.setScopeJson("[\"src/A.java\"]");
        r.setStrategySnapshotJson(
                "{\"analyzerType\":1,\"model\":{\"baseUrl\":\"http://x\",\"apiKey\":\"k\",\"modelName\":\"m\"}}");
        r.setStartedAt(startedAt);
        return r;
    }

    private static Project project() {
        Project p = new Project();
        p.setId(9L);
        p.setGiteaUrl("http://gitea.local/team/repo");
        return p;
    }

    private static AnalyzeOutcome outcome() {
        ObjectNode result = MAPPER.createObjectNode();
        result.put("summary", "ok");
        return new AnalyzeOutcome(result, ReviewStatus.SUCCESS);
    }

    private AnalysisContext.Deadline deadlineFrom(ReviewRecord r) {
        when(recordMapper.selectById(1L)).thenReturn(r);
        executor.execute(1L);
        ArgumentCaptor<AnalysisContext> captor = ArgumentCaptor.forClass(AnalysisContext.class);
        try {
            org.mockito.Mockito.verify(analyzer).analyze(captor.capture());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        return captor.getValue().deadline();
    }

    @Test
    void deadlineIsTheConfiguredMinutesFromStartedAt() {
        AnalysisContext.Deadline deadline = deadlineFrom(record(LocalDateTime.now().minusMinutes(10)));

        assertEquals(50, deadline.minutes());
        assertFalse(deadline.exceeded(), "跑了 10 分钟还没到 50 分钟上限");
    }

    @Test
    void recordOlderThanTheTimeoutIsAlreadyExpired() {
        AnalysisContext.Deadline deadline = deadlineFrom(record(LocalDateTime.now().minusMinutes(60)));

        assertTrue(deadline.exceeded(), "已跑 60 分钟 > 50 分钟上限，必须判定超时");
    }

    @Test
    void missingStartedAtFallsBackToNow() {
        AnalysisContext.Deadline deadline = deadlineFrom(record(null));

        assertFalse(deadline.exceeded(), "没有 started_at（历史数据）时按当前时刻起算");
    }

    @Test
    void configuredTimeoutIsHonored() {
        props.setTaskTimeoutMinutes(5);

        AnalysisContext.Deadline deadline = deadlineFrom(record(LocalDateTime.now().minusMinutes(10)));

        assertEquals(5, deadline.minutes());
        assertTrue(deadline.exceeded());
    }
}
