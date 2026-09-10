package com.codereview.dto;

/**
 * 准确率统计（按「项目 × 分析器类型」维度，服务验收指标 5）。
 *
 * @param analyzerType     1 llm-review / 2 coupling / 3 design-pattern / 4 api-review / 5 diff-review
 * @param analyzerName     展示名
 * @param category         {@code static}（基础静态规则，即 api-review/SonarQube）/ {@code ai}（AI 规则）
 *                         —— 指标 5 要求两类规则<b>分别</b>复核，故在此显式区分
 * @param totalIssues      该分析器产出的 issue 总数
 * @param markedIssues     已标记（复核过）的 issue 数
 * @param falsePositives   标记为误报的数量
 * @param accepted         标记为已采纳的数量
 * @param overallAccuracy  整体准确率 = (总数 − 误报) / 总数；<b>未标记视为正确</b>，会偏高，故必须与覆盖率同看
 * @param reviewedAccuracy 已复核准确率 = 已采纳 / (已采纳 + 误报)；只统计人工标记过的
 * @param reviewCoverage   复核覆盖率 = 已标记 / 总数
 */
public record AccuracyStat(
        int analyzerType,
        String analyzerName,
        String category,
        long totalIssues,
        long markedIssues,
        long falsePositives,
        long accepted,
        double overallAccuracy,
        double reviewedAccuracy,
        double reviewCoverage) {
}
