package com.codereview.analyzer;

import com.fasterxml.jackson.databind.JsonNode;

/** 分析结果：result 写入 result_json，status 为审查记录状态（2 成功 / 3 失败 / 4 部分成功）。 */
public record AnalyzeOutcome(JsonNode result, int status) {
}
