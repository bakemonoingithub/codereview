package com.codereview.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
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
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 落库失败时**记录不能卡在"执行中"**。
 *
 * <p>回归背景（现场实测 MySQL 8.0.39 严格模式）：`result_json` 是 TEXT = 65535 字节，
 * 而写进去的是未截断的 LLM 原文；超限写入是**硬失败 1406**，不是静默截断。
 * 早先的实现让异常冒泡到 {@code execute} 的 catch 再调 {@code markFailed}，
 * 而那次失败写用的实体**还带着刚刚落库失败的超大 result_json** ⇒ 第二次同样失败 ⇒
 * 异常逃出 {@code @Async} 方法 ⇒ 记录永久停在 status=1/progress=0，接口层面零错误可见。
 *
 * <p>现在：落库失败在 {@code persist} 内就地转成"失败 + 原因"，且失败写不再携带大载荷。
 */
class ReviewExecutorResilienceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 模拟 TEXT 列的 65535 字节上限（按字符近似） */
    private static final int TEXT_LIMIT = 65_535;

    private ReviewRecordMapper recordMapper;
    private ProjectMapper projectMapper;
    private Analyzer analyzer;
    private ReviewExecutor executor;
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void setUp() {
        recordMapper = mock(ReviewRecordMapper.class);
        projectMapper = mock(ProjectMapper.class);
        analyzer = mock(Analyzer.class);
        when(analyzer.type()).thenReturn(1);
        executor = new ReviewExecutor(recordMapper, projectMapper, List.of(analyzer));

        Logger logger = (Logger) LoggerFactory.getLogger(ReviewExecutor.class);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
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

    /** 一条 payloadChars 字符的结果 JSON（raw 里塞满内容，模拟未截断的 LLM 原文） */
    private static AnalyzeOutcome outcome(int payloadChars) {
        ObjectNode result = MAPPER.createObjectNode();
        result.put("summary", "结果摘要");
        ObjectNode unit = result.putArray("units").addObject();
        unit.put("raw", "x".repeat(Math.max(0, payloadChars)));
        return new AnalyzeOutcome(result, ReviewStatus.SUCCESS);
    }

    /**
     * 让 mapper 像真实 MySQL 那样对超长 result_json 报 1406：
     * 超过 TEXT_LIMIT 的写入抛异常，其余正常。
     */
    private void rejectOversizedResultJsonWrites() {
        when(recordMapper.updateById(any(ReviewRecord.class))).thenAnswer(invocation -> {
            ReviewRecord r = invocation.getArgument(0);
            String json = r.getResultJson();
            if (json != null && json.length() > TEXT_LIMIT) {
                throw new org.springframework.dao.DataIntegrityViolationException(
                        "could not execute statement",
                        new java.sql.SQLException("Data truncation: Data too long for column 'result_json' at row 1",
                                "22001", 1406));
            }
            return 1;
        });
    }

    @Test
    void oversizedResultFailsTheRecordInsteadOfLeavingItRunning() throws Exception {
        ReviewRecord r = record("[\"src/A.java\"]");
        when(recordMapper.selectById(1L)).thenReturn(r);
        when(projectMapper.selectById(9L)).thenReturn(project());
        when(analyzer.analyze(any())).thenReturn(outcome(TEXT_LIMIT + 100));
        rejectOversizedResultJsonWrites();

        // 关键：异常不得逃出 @Async 方法（逃出去就没人处理，记录会永远停在"执行中"）
        executor.execute(1L);

        assertEquals(ReviewStatus.FAILED, r.getStatus(), "落库失败必须落到失败态");
        assertEquals(100, r.getProgress());
        assertNotNull(r.getFinishedAt(), "失败也要有结束时间，否则耗时无从统计");
        assertNotNull(r.getErrorMessage(), "失败必须给出原因");
        assertTrue(r.getErrorMessage().contains("结果落库失败"), "实际：" + r.getErrorMessage());
        assertTrue(r.getErrorMessage().contains("Data too long"),
                "原因要带上最内层数据库报错，实际：" + r.getErrorMessage());
        // 失败写不再携带超大载荷 —— 否则那次 update 会再次失败，记录照样卡住
        assertNull(r.getResultJson());
    }

    @Test
    void failureWriteNeverCarriesTheOversizedPayload() throws Exception {
        ReviewRecord r = record("[\"src/A.java\"]");
        when(recordMapper.selectById(1L)).thenReturn(r);
        when(projectMapper.selectById(9L)).thenReturn(project());
        when(analyzer.analyze(any())).thenReturn(outcome(TEXT_LIMIT + 100));
        rejectOversizedResultJsonWrites();

        executor.execute(1L);

        // 每一次交给 mapper 的写入都必须能在真实 MySQL 上成功
        org.mockito.Mockito.verify(recordMapper, org.mockito.Mockito.atLeastOnce()).updateById(any(ReviewRecord.class));
        assertTrue(r.getResultJson() == null || r.getResultJson().length() <= TEXT_LIMIT,
                "失败态的实体不允许再带着超长 result_json");
    }

    @Test
    void analysisFailureRecordsTheReasonAndKeepsTheOldResult() throws Exception {
        ReviewRecord r = record("[\"src/A.java\"]");
        // 重审场景：实体是从库里读出来的，带着上一次成功的结果
        r.setResultJson("{\"summary\":\"上一次的结果\"}");
        r.setErrorMessage(null);
        when(recordMapper.selectById(1L)).thenReturn(r);
        when(projectMapper.selectById(9L)).thenReturn(project());
        when(analyzer.retry(any(), any())).thenThrow(new IllegalStateException("网关返回 502"));

        executor.retry(1L);

        assertEquals(ReviewStatus.FAILED, r.getStatus());
        assertTrue(r.getErrorMessage().contains("重审失败"), "实际：" + r.getErrorMessage());
        assertTrue(r.getErrorMessage().contains("网关返回 502"),
                "原因要能看出是网关问题，实际：" + r.getErrorMessage());
        // 失败路径把 result_json 置空 = 不更新该列（MyBatis-Plus 默认 NOT_NULL 策略）⇒ 旧结果留在库里
        assertNull(r.getResultJson(), "失败不应覆盖上一次成功的结果");
    }

    @Test
    void successClearsTheStaleFailureReason() throws Exception {
        ReviewRecord r = record("[\"src/A.java\"]");
        r.setErrorMessage("上一次失败的原因");
        when(recordMapper.selectById(1L)).thenReturn(r);
        when(projectMapper.selectById(9L)).thenReturn(project());
        when(analyzer.retry(any(), any())).thenReturn(outcome(10));

        executor.retry(1L);

        assertEquals(ReviewStatus.SUCCESS, r.getStatus());
        assertNull(r.getErrorMessage(), "重审成功后不该留着上一次的失败原因");
        assertNotNull(r.getResultJson());
    }

    @Test
    void errorMessageUsesIgnoredStrategySoNullReallyClearsTheColumn() throws Exception {
        Field field = ReviewRecord.class.getDeclaredField("errorMessage");
        TableField annotation = field.getAnnotation(TableField.class);

        assertNotNull(annotation, "errorMessage 需要有 @TableField 才谈得上策略");
        assertEquals(FieldStrategy.IGNORED, annotation.updateStrategy(),
                "默认的 NOT_NULL 策略下 setErrorMessage(null) 等于不更新该列，"
                        + "旧的失败原因会永远留在库里（界面显示\"成功 + 失败原因\"）");
    }

    @Test
    void oversizedResultIsLoggedWithItsSize() throws Exception {
        ReviewRecord r = record("[\"src/A.java\"]");
        when(recordMapper.selectById(1L)).thenReturn(r);
        when(projectMapper.selectById(9L)).thenReturn(project());
        when(analyzer.analyze(any())).thenReturn(outcome(ReviewExecutor.RESULT_WARN_BYTES + 1000));
        // 这次不模拟列上限：MEDIUMTEXT 存得下，只验证告警日志
        when(recordMapper.updateById(any(ReviewRecord.class))).thenReturn(1);

        executor.execute(1L);

        assertEquals(ReviewStatus.SUCCESS, r.getStatus());
        boolean warned = logs.list.stream()
                .anyMatch(e -> e.getLevel() == Level.WARN && e.getFormattedMessage().contains("体积异常"));
        assertTrue(warned, "超过阈值要留一条 WARN，日志：" + logs.list);
    }

    @Test
    void normalSizeResultIsNotWarnedAbout() throws Exception {
        ReviewRecord r = record("[\"src/A.java\"]");
        when(recordMapper.selectById(1L)).thenReturn(r);
        when(projectMapper.selectById(9L)).thenReturn(project());
        when(analyzer.analyze(any())).thenReturn(outcome(50_000));
        when(recordMapper.updateById(any(ReviewRecord.class))).thenReturn(1);

        executor.execute(1L);

        assertEquals(ReviewStatus.SUCCESS, r.getStatus());
        assertEquals(0, logs.list.stream().filter(e -> e.getLevel() == Level.WARN).count(),
                "正常体积不该刷告警，日志：" + logs.list);
    }

    @Test
    void failureReasonIsTruncatedToTheColumnWidth() {
        String tooLong = "原".repeat(1500);

        String bounded = ReviewExecutor.bounded(tooLong);

        assertEquals(ReviewExecutor.ERROR_MESSAGE_MAX, bounded.length(),
                "error_message 是有界列，写入侧必须自己截断");
        assertTrue(bounded.endsWith("…"), "截断要有可见标记");
        assertNull(ReviewExecutor.bounded(null));
        assertEquals("短原因", ReviewExecutor.bounded("短原因"));
    }
}
