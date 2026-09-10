package com.codereview.analyzer;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 分析器 SPI（简化版）。type 对应 review_strategy.analyzer_type：
 * 1 llm-review / 2 coupling / 3 design-pattern / 4 api-review / 5 diff-review。
 */
public interface Analyzer {

    int type();

    AnalyzeOutcome analyze(AnalysisContext ctx) throws Exception;

    /** 重审：默认整条重跑；llm-review 覆盖为「只重跑失败单元并合并」。 */
    default AnalyzeOutcome retry(AnalysisContext ctx, JsonNode oldResult) throws Exception {
        return analyze(ctx);
    }
}
