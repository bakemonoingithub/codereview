package com.codereview.service;

import com.codereview.dto.AccuracyStat;
import com.codereview.entity.IssueMark;
import com.codereview.entity.ReviewRecord;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 准确率统计口径：双指标 + 覆盖率，并按指标 5 的要求区分 static / ai 两类规则。 */
class IssueMarkServiceTest {

    @Test
    void accuracySplitsStaticAndAiRulesAndComputesThreeMetrics() {
        // 记录1：api-review（基础静态规则）3 个 issue，其中 1 个被标为误报
        // 记录2：diff-review（AI 规则）2 个 issue，其中 1 个被标为已采纳
        ReviewRecord staticRecord = record(1L, 4, "{\"units\":[{\"issues\":[{},{},{}]}]}");
        ReviewRecord aiRecord = record(2L, 5, "{\"units\":[{\"issues\":[{},{}]}]}");

        List<AccuracyStat> stats = IssueMarkService.compute(List.of(staticRecord, aiRecord),
                List.of(mark(1L, IssueMarkService.MARK_FALSE_POSITIVE),
                        mark(2L, IssueMarkService.MARK_ACCEPTED)));

        assertEquals(2, stats.size());

        AccuracyStat staticStat = stats.stream().filter(s -> s.analyzerType() == 4).findFirst().orElseThrow();
        assertEquals("static", staticStat.category(), "api-review 属基础静态规则");
        assertEquals(3, staticStat.totalIssues());
        assertEquals(1, staticStat.markedIssues());
        assertEquals(1, staticStat.falsePositives());
        assertEquals(0, staticStat.accepted());
        assertEquals(0.6667, staticStat.overallAccuracy(), 1e-4, "整体 = (3-1)/3，未标记视为正确");
        assertEquals(0.0, staticStat.reviewedAccuracy(), 1e-4, "已复核 = 0/(0+1)");
        assertEquals(0.3333, staticStat.reviewCoverage(), 1e-4, "覆盖率 = 1/3");

        AccuracyStat aiStat = stats.stream().filter(s -> s.analyzerType() == 5).findFirst().orElseThrow();
        assertEquals("ai", aiStat.category());
        assertEquals(2, aiStat.totalIssues());
        assertEquals(1, aiStat.accepted());
        assertEquals(1.0, aiStat.overallAccuracy(), 1e-4);
        assertEquals(1.0, aiStat.reviewedAccuracy(), 1e-4);
        assertEquals(0.5, aiStat.reviewCoverage(), 1e-4);
    }

    @Test
    void clearedMarksAreNotCounted() {
        ReviewRecord record = record(1L, 5, "{\"units\":[{\"issues\":[{}]}]}");

        List<AccuracyStat> stats = IssueMarkService.compute(List.of(record),
                List.of(mark(1L, IssueMarkService.MARK_NONE)));

        assertEquals(1, stats.size());
        assertEquals(0, stats.get(0).markedIssues(), "markValue=0 表示已撤销，不应计入复核");
        assertEquals(1.0, stats.get(0).overallAccuracy(), 1e-4);
    }

    @Test
    void marksPointingAtUnknownRecordsAreIgnored() {
        ReviewRecord record = record(1L, 5, "{\"units\":[{\"issues\":[{}]}]}");

        List<AccuracyStat> stats = IssueMarkService.compute(List.of(record),
                List.of(mark(99L, IssueMarkService.MARK_FALSE_POSITIVE)));

        assertEquals(1, stats.size());
        assertEquals(0, stats.get(0).markedIssues());
        assertEquals(0, stats.get(0).falsePositives());
    }

    @Test
    void recordsWithNoCountableIssuesProduceNoRow() {
        assertTrue(IssueMarkService.compute(List.of(record(1L, 1, "not json")), List.of()).isEmpty());
        assertTrue(IssueMarkService.compute(List.of(record(1L, 1, null)), List.of()).isEmpty());
        assertTrue(IssueMarkService.compute(List.of(record(1L, 1, "{\"units\":[]}")), List.of()).isEmpty());
    }

    @Test
    void missingAnalyzerTypeFallsBackToUnknownBucket() {
        ReviewRecord record = new ReviewRecord();
        record.setId(1L);
        record.setResultJson("{\"units\":[{\"issues\":[{}]}]}");

        List<AccuracyStat> stats = IssueMarkService.compute(List.of(record), List.of());

        assertEquals(1, stats.size());
        assertEquals(0, stats.get(0).analyzerType());
        assertEquals("未知分析器", stats.get(0).analyzerName());
    }

    private static ReviewRecord record(Long id, int analyzerType, String resultJson) {
        ReviewRecord r = new ReviewRecord();
        r.setId(id);
        r.setStrategySnapshotJson("{\"analyzerType\":" + analyzerType + "}");
        r.setResultJson(resultJson);
        return r;
    }

    private static IssueMark mark(Long recordId, int value) {
        IssueMark m = new IssueMark();
        m.setRecordId(recordId);
        m.setMarkValue(value);
        return m;
    }
}
