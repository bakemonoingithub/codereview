package com.codereview.service;

import com.codereview.config.ReportProperties;
import com.codereview.entity.ReviewRecord;
import com.codereview.review.ReviewStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 报告聚合的预算裁剪（backlog ③）：原先把每条记录的完整 {@code result_json} 原样拼进提示词，
 * 勾选一多就无界增长。这里把规则钉死：去重、跳过无结果的、单条截断、超预算整条省略、
 * **任何情况下都不超过额度**。
 */
class ReportAggregationBuilderTest {

    private ReportProperties props;
    private ReportAggregationBuilder builder;

    @BeforeEach
    void setUp() {
        props = new ReportProperties();
        props.setRecordMaxChars(20_000);
        builder = new ReportAggregationBuilder(props);
    }

    private void useRecordCap(int cap) {
        props.setRecordMaxChars(cap);
        builder = new ReportAggregationBuilder(props);
    }

    private static ReviewRecord record(long id, int status, String json) {
        ReviewRecord r = new ReviewRecord();
        r.setId(id);
        r.setStatus(status);
        r.setResultJson(json);
        return r;
    }

    private static ReviewRecord ok(long id, String json) {
        return record(id, ReviewStatus.SUCCESS, json);
    }

    /** 长度约 {@code size} 字符的结果 JSON 文本。 */
    private static String json(int size) {
        return "{\"summary\":\"" + "x".repeat(Math.max(1, size - 15)) + "\"}";
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
    void keepsEverythingAndStaysSilentWhenWithinBudget() {
        ReportAggregationBuilder.Result result = builder.build(
                List.of(ok(1, "{\"summary\":\"a\"}"), ok(2, "{\"summary\":\"b\"}")), 10_000);

        assertTrue(result.text().contains("记录 1"));
        assertTrue(result.text().contains("记录 2"));
        assertFalse(result.clipped(), "没裁剪就不该出现预算说明");
        assertFalse(result.text().contains("预算说明"));
        assertEquals(2, result.included());
        assertEquals(0, result.omitted());
        assertEquals(0, result.skipped());
    }

    @Test
    void truncatesSingleRecordBeyondPerRecordCap() {
        useRecordCap(500);

        ReportAggregationBuilder.Result result = builder.build(List.of(ok(1, json(5_000))), 100_000);

        assertEquals(1, result.truncated());
        assertEquals(1, result.included());
        assertTrue(result.text().contains("已按单条上限截断"), result.text());
        assertTrue(result.text().length() <= 100_000);
    }

    @Test
    void omitsRecordsThatNoLongerFitAndSaysHowMany() {
        useRecordCap(2_000);

        ReportAggregationBuilder.Result result = builder.build(
                List.of(ok(1, json(400)), ok(2, json(400)), ok(3, json(400))), 1_500);

        assertEquals(1, result.included(), "额度只够第一条（第二条会把它顶出预算）");
        assertEquals(2, result.omitted());
        assertTrue(result.text().contains("省略 2 条"), result.text());
        assertTrue(result.text().length() <= 1_500);
    }

    @Test
    void neverExceedsBudgetEvenWhenEvenTheNoticeDoesNotFit() {
        ReportAggregationBuilder.Result result = builder.build(List.of(ok(1, json(1_000))), 50);

        assertTrue(result.text().length() <= 50, "实际长度 " + result.text().length());
        assertEquals(0, result.included());
        assertEquals(1, result.omitted());
    }

    @Test
    void deduplicatesByIdKeepingFirstOccurrenceOrder() {
        ReportAggregationBuilder.Result result = builder.build(
                List.of(ok(2, "{\"summary\":\"b\"}"), ok(1, "{\"summary\":\"a\"}"),
                        ok(2, "{\"summary\":\"b-again\"}")), 10_000);

        assertEquals(2, result.included());
        assertEquals(1, countOccurrences(result.text(), "记录 2"));
        assertTrue(result.text().indexOf("记录 2") < result.text().indexOf("记录 1"),
                "首次出现顺序要保留：" + result.text());
        assertFalse(result.text().contains("b-again"), "重复 id 只取第一次");
    }

    @Test
    void skipsFailedAndEmptyResultsAndMentionsIt() {
        ReportAggregationBuilder.Result result = builder.build(List.of(
                ok(1, "{\"summary\":\"a\"}"),
                record(2, ReviewStatus.FAILED, "{\"summary\":\"failed\"}"),
                record(3, ReviewStatus.SUCCESS, "   ")), 10_000);

        assertEquals(1, result.included());
        assertEquals(2, result.skipped());
        assertTrue(result.text().contains("跳过 2 条"), result.text());
        assertFalse(result.text().contains("记录 2"));
        assertFalse(result.text().contains("记录 3"));
    }

    @Test
    void partialStatusIsStillAggregated() {
        // 部分成功（4）有可用结果，不能当失败跳过
        ReportAggregationBuilder.Result result = builder.build(List.of(
                record(1, ReviewStatus.PARTIAL, "{\"summary\":\"half\"}")), 10_000);

        assertEquals(1, result.included());
        assertEquals(0, result.skipped());
    }

    @Test
    void emptyRecordsProduceEmptyText() {
        ReportAggregationBuilder.Result result = builder.build(List.of(), 1_000);

        assertEquals("", result.text());
        assertFalse(result.clipped());
    }
}
