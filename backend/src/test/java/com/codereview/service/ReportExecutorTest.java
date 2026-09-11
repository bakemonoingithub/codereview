package com.codereview.service;

import com.codereview.entity.ModelConfig;
import com.codereview.entity.Report;
import com.codereview.llm.LlmClient;
import com.codereview.mapper.ModelConfigMapper;
import com.codereview.mapper.PromptMapper;
import com.codereview.mapper.PromptVersionMapper;
import com.codereview.mapper.ReportMapper;
import com.codereview.mapper.ReportRecordMapper;
import com.codereview.mapper.ReviewRecordMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
        modelConfigMapper = mock(ModelConfigMapper.class);
        llmClient = mock(LlmClient.class);
        executor = new ReportExecutor(reportMapper, reportRecordMapper, mock(ReviewRecordMapper.class),
                modelConfigMapper, mock(PromptMapper.class), mock(PromptVersionMapper.class), llmClient);

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
}
