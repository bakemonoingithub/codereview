package com.codereview.service;

import com.codereview.config.ReportProperties;
import com.codereview.entity.ModelConfig;
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
import com.codereview.review.ReviewStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 报告耗时的落库（A 批 E3）。
 *
 * <p>验收指标 8 的验证方法是"5 个 900 行文件批量分析，**记录完整耗时**"，
 * 而 {@code report} 表原先连 {@code started_at}/{@code finished_at} 都没有 ——
 * 报告耗时无处可查，只能靠现场秒表。这里守住"开始与结束都要写入、失败也要写"。
 *
 * <p>注意：断言用的是**每次 updateById 调用时刻的快照**，而不是 {@code ArgumentCaptor}
 * 捕获的对象引用 —— 前者会被后续 setter 一起改掉，读到的永远是最终态，等于没验证写入内容。
 */
class ReportExecutorTest {

    private ReportMapper reportMapper;
    private ReportRecordMapper reportRecordMapper;
    private ReviewRecordMapper reviewRecordMapper;
    private ModelConfigMapper modelConfigMapper;
    private LlmClient llmClient;
    private ReportExecutor executor;
    private Report report;
    /** 每次落库的时刻快照，按调用顺序 */
    private final List<Report> writes = new ArrayList<>();

    @BeforeEach
    void setUp() {
        writes.clear();
        reportMapper = mock(ReportMapper.class);
        reportRecordMapper = mock(ReportRecordMapper.class);
        reviewRecordMapper = mock(ReviewRecordMapper.class);
        modelConfigMapper = mock(ModelConfigMapper.class);
        llmClient = mock(LlmClient.class);
        executor = newExecutor(new ReportProperties());

        report = new Report();
        report.setId(9001L);
        report.setProjectId(9L);
        when(reportMapper.selectById(9001L)).thenReturn(report);
        when(reportRecordMapper.selectList(any())).thenReturn(List.of());
        // 关键：在调用时刻拷贝一份，否则后续 setter 会把历史写入一起改掉
        when(reportMapper.updateById(any(Report.class))).thenAnswer(invocation -> {
            writes.add(snapshot(invocation.getArgument(0)));
            return 1;
        });

        ModelConfig model = new ModelConfig();
        model.setBaseUrl("http://llm.local");
        model.setToken("sk-test");
        model.setModelName("deepseek-chat");
        when(modelConfigMapper.selectById(anyLong())).thenReturn(model);
    }

    private ReportExecutor newExecutor(ReportProperties props) {
        return new ReportExecutor(reportMapper, reportRecordMapper, reviewRecordMapper, modelConfigMapper,
                mock(PromptMapper.class), mock(PromptVersionMapper.class), llmClient,
                props, new ReportAggregationBuilder(props));
    }

    private static Report snapshot(Report source) {
        Report copy = new Report();
        copy.setId(source.getId());
        copy.setStatus(source.getStatus());
        copy.setProgress(source.getProgress());
        copy.setStartedAt(source.getStartedAt());
        copy.setFinishedAt(source.getFinishedAt());
        return copy;
    }

    private Report firstWrite() {
        return writes.get(0);
    }

    private Report lastWrite() {
        return writes.get(writes.size() - 1);
    }

    @Test
    void startedAtIsWrittenWhenWorkBeginsNotWhenQueued() {
        when(llmClient.chat(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn("正文");

        executor.execute(9001L, 1L, null);

        assertEquals(2, writes.size(), "应落库两次：进入生成中 + 结束");
        Report starting = firstWrite();
        assertEquals(1, starting.getStatus(), "首次落库应进入生成中");
        assertNotNull(starting.getStartedAt(), "开始时间应在任务真正开始时写入（排队时间不计入）");
        assertNull(starting.getFinishedAt(), "刚开始时不应有结束时间");
    }

    @Test
    void successWritesFinishedAt() {
        when(llmClient.chat(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn("# 概述\n报告正文");

        executor.execute(9001L, 1L, null);

        Report finished = lastWrite();
        assertEquals(2, finished.getStatus(), "成功应置 status=2");
        assertEquals(100, finished.getProgress());
        assertNotNull(finished.getStartedAt());
        assertNotNull(finished.getFinishedAt(), "成功时必须写入结束时间");
        assertTrue(!finished.getFinishedAt().isBefore(finished.getStartedAt()),
                "结束时间不应早于开始时间");
    }

    @Test
    void failureAlsoWritesFinishedAt() {
        // LLM 调用失败（如超时/网关错误）——失败报告的耗时同样要能查到
        when(llmClient.chat(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("网关不可用"));

        executor.execute(9001L, 1L, null);

        Report finished = lastWrite();
        assertEquals(3, finished.getStatus(), "失败应置 status=3");
        assertNotNull(finished.getStartedAt(), "失败时也应有开始时间");
        assertNotNull(finished.getFinishedAt(), "失败时也必须写入结束时间");
    }

    @Test
    void missingReportDoesNotExplode() {
        when(reportMapper.selectById(anyLong())).thenReturn(null);

        executor.execute(404L, 1L, null);

        assertTrue(writes.isEmpty(), "报告不存在时不应有任何落库");
        // BaseMapper 的 updateById 有 T/Collection 两个重载，必须指明类型否则 any() 有歧义
        verify(reportMapper, never()).updateById(any(Report.class));
    }

    // ---------------- backlog ③：批量取数 + 提示词预算 ----------------

    private static ReportRecord link(long recordId) {
        ReportRecord l = new ReportRecord();
        l.setReportId(9001L);
        l.setRecordId(recordId);
        return l;
    }

    private static ReviewRecord record(long id, int status, String json) {
        ReviewRecord r = new ReviewRecord();
        r.setId(id);
        r.setStatus(status);
        r.setResultJson(json);
        return r;
    }

    private String capturePrompt() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(llmClient).chat(anyString(), anyString(), anyString(), anyString(), captor.capture());
        return captor.getValue();
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    @Test
    void loadsSelectedRecordsInOneBatchQueryInsteadOfPerLink() {
        when(reportRecordMapper.selectList(any()))
                .thenReturn(List.of(link(11L), link(22L), link(33L)));
        // IN 查询不保证顺序：故意倒序返回，验证最终仍按 link 顺序拼接
        when(reviewRecordMapper.selectList(any())).thenReturn(List.of(
                record(33L, ReviewStatus.SUCCESS, "{\"summary\":\"c\"}"),
                record(22L, ReviewStatus.SUCCESS, "{\"summary\":\"b\"}"),
                record(11L, ReviewStatus.SUCCESS, "{\"summary\":\"a\"}")));
        when(llmClient.chat(anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn("正文");

        executor.execute(9001L, 1L, null);

        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<ReviewRecord>> wrapperCaptor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.QueryWrapper.class);
        verify(reviewRecordMapper, times(1)).selectList(wrapperCaptor.capture());
        verify(reviewRecordMapper, never()).selectById(anyLong());
        String projection = wrapperCaptor.getValue().getSqlSelect();
        assertTrue(projection.contains("strategy_snapshot_json"), projection);
        assertTrue(projection.contains("result_json"), projection);
        assertFalse(projection.contains("scope_json"),
                "不该把用不到的列（含大字段）一起拉回来：" + projection);
        String prompt = capturePrompt();
        assertTrue(prompt.indexOf("记录 11") < prompt.indexOf("记录 22"), prompt);
        assertTrue(prompt.indexOf("记录 22") < prompt.indexOf("记录 33"), prompt);
    }

    @Test
    void deduplicatesRepeatedRecordIds() {
        when(reportRecordMapper.selectList(any())).thenReturn(List.of(link(11L), link(11L)));
        when(reviewRecordMapper.selectList(any())).thenReturn(List.of(
                record(11L, ReviewStatus.SUCCESS, "{\"summary\":\"a\"}")));
        when(llmClient.chat(anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn("正文");

        executor.execute(9001L, 1L, null);

        assertEquals(1, countOccurrences(capturePrompt(), "记录 11"));
    }

    @Test
    void skipsFailedRecordsAndSaysSoInThePrompt() {
        when(reportRecordMapper.selectList(any())).thenReturn(List.of(link(11L), link(22L)));
        when(reviewRecordMapper.selectList(any())).thenReturn(List.of(
                record(11L, ReviewStatus.SUCCESS, "{\"summary\":\"ok\"}"),
                record(22L, ReviewStatus.FAILED, "")));
        when(llmClient.chat(anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn("正文");

        executor.execute(9001L, 1L, null);

        String prompt = capturePrompt();
        assertTrue(prompt.contains("跳过 1 条"), prompt);
        assertFalse(prompt.contains("记录 22"), prompt);
    }

    @Test
    void promptNeverExceedsTheConfiguredBudget() {
        ReportProperties small = new ReportProperties();
        small.setPromptMaxChars(300);
        small.setRecordMaxChars(200);
        executor = newExecutor(small);
        when(reportRecordMapper.selectList(any())).thenReturn(List.of(link(11L), link(22L)));
        when(reviewRecordMapper.selectList(any())).thenReturn(List.of(
                record(11L, ReviewStatus.SUCCESS, "{\"summary\":\"" + "a".repeat(2_000) + "\"}"),
                record(22L, ReviewStatus.SUCCESS, "{\"summary\":\"" + "b".repeat(2_000) + "\"}")));
        when(llmClient.chat(anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn("正文");

        executor.execute(9001L, 1L, null);

        String prompt = capturePrompt();
        assertTrue(prompt.length() <= 300, "实际长度 " + prompt.length());
    }

    @Test
    void promptIsHardTruncatedWhenTemplateAloneExceedsBudget() {
        ReportProperties tiny = new ReportProperties();
        tiny.setPromptMaxChars(100);
        executor = newExecutor(tiny);
        when(reportRecordMapper.selectList(any())).thenReturn(List.of(link(11L)));
        when(reviewRecordMapper.selectList(any())).thenReturn(List.of(
                record(11L, ReviewStatus.SUCCESS, "{\"summary\":\"a\"}")));
        when(llmClient.chat(anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn("正文");

        executor.execute(9001L, 1L, null);

        assertTrue(capturePrompt().length() <= 100, "模板本身就可能超预算，必须有最终硬截断");
    }
}
