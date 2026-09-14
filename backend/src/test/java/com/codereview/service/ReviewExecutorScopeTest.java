package com.codereview.service;

import com.codereview.analyzer.AnalysisContext;
import com.codereview.analyzer.AnalyzeOutcome;
import com.codereview.analyzer.Analyzer;
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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 编排层的**可审查过滤**。
 *
 * <p>判定规则本身由 {@code ReviewableFilesTest} 覆盖；这里测的是"它有没有真的被用上"——
 * 过滤写在编排层是为了四个分析器共享一处实现，但**接线错了照样会漏**，
 * 而漏的后果是二进制文件的内容被整段送进大模型。
 */
class ReviewExecutorScopeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ReviewRecordMapper recordMapper;
    private ProjectMapper projectMapper;
    private Analyzer analyzer;
    private ReviewExecutor executor;

    @BeforeEach
    void setUp() {
        recordMapper = mock(ReviewRecordMapper.class);
        projectMapper = mock(ProjectMapper.class);
        analyzer = mock(Analyzer.class);
        when(analyzer.type()).thenReturn(1);
        executor = new ReviewExecutor(recordMapper, projectMapper, List.of(analyzer));
    }

    private ReviewRecord record(String scopeJson) {
        ReviewRecord r = new ReviewRecord();
        r.setId(1L);
        r.setProjectId(9L);
        r.setScopeJson(scopeJson);
        r.setStrategySnapshotJson(
                "{\"analyzerType\":1,\"model\":{\"baseUrl\":\"http://x\",\"apiKey\":\"k\",\"modelName\":\"m\"}}");
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
        result.put("summary", "原摘要");
        return new AnalyzeOutcome(result, ReviewStatus.SUCCESS);
    }

    @Test
    void filtersNonReviewableFilesBeforeDispatch() throws Exception {
        ReviewRecord r = record("[\"src/A.java\",\"assets/logo.png\",\"tool.exe\"]");
        when(recordMapper.selectById(1L)).thenReturn(r);
        when(projectMapper.selectById(9L)).thenReturn(project());
        when(analyzer.analyze(any())).thenReturn(outcome());

        executor.execute(1L);

        ArgumentCaptor<AnalysisContext> captor = ArgumentCaptor.forClass(AnalysisContext.class);
        verify(analyzer).analyze(captor.capture());
        // 只有白名单内的文件被交给分析器；二进制根本没进去
        assertEquals(List.of("src/A.java"), captor.getValue().scope());

        // 记录里说明跳过了几个 —— 否则"范围 3 个、结果 1 个单元"事后无法解释
        assertTrue(r.getResultJson().contains("原摘要；已跳过 2 个非可审查文件"),
                "summary 应说明跳过的数量，实际：" + r.getResultJson());
    }

    @Test
    void doesNotTouchSummaryWhenNothingSkipped() throws Exception {
        ReviewRecord r = record("[\"src/A.java\",\"pom.xml\"]");
        when(recordMapper.selectById(1L)).thenReturn(r);
        when(projectMapper.selectById(9L)).thenReturn(project());
        when(analyzer.analyze(any())).thenReturn(outcome());

        executor.execute(1L);

        assertTrue(r.getResultJson().contains("原摘要"), "没有跳过就不该改动 summary");
        assertTrue(!r.getResultJson().contains("已跳过"), "没有跳过就不该出现跳过说明");
    }

    /**
     * 勾了文件、但一个都不在白名单内：在编排层就拒绝，**不调用分析器**。
     * （正常入口已被 {@code ReviewService} 拦下，这里是绕过前端的兜底。）
     */
    @Test
    void rejectsWhenEverySelectedFileIsNotReviewable() throws Exception {
        ReviewRecord r = record("[\"assets/logo.png\",\"tool.exe\"]");
        when(recordMapper.selectById(1L)).thenReturn(r);
        when(projectMapper.selectById(9L)).thenReturn(project());

        executor.execute(1L);

        verify(analyzer, never()).analyze(any());
        assertEquals(ReviewStatus.FAILED, r.getStatus());
        assertTrue(r.getResultJson().contains("没有可审查的文件"), "失败原因要写进记录：" + r.getResultJson());
    }

    /**
     * 空范围是**合法**的：diff 审查允许不勾具体文件，含义是"审全部变更文件"。
     * 不能把它误判成"没有可审查的文件"。
     */
    @Test
    void emptyScopeIsNotTreatedAsAllFilteredOut() throws Exception {
        ReviewRecord r = record("[]");
        when(recordMapper.selectById(1L)).thenReturn(r);
        when(projectMapper.selectById(9L)).thenReturn(project());
        when(analyzer.analyze(any())).thenReturn(outcome());

        executor.execute(1L);

        ArgumentCaptor<AnalysisContext> captor = ArgumentCaptor.forClass(AnalysisContext.class);
        verify(analyzer).analyze(captor.capture());
        assertEquals(List.of(), captor.getValue().scope());
        assertEquals(ReviewStatus.SUCCESS, r.getStatus());
    }
}
