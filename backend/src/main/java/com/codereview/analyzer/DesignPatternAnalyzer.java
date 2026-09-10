package com.codereview.analyzer;

import com.codereview.llm.LlmClient;
import com.codereview.material.Material;
import com.codereview.material.MaterialService;
import com.codereview.review.ReviewStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * design-pattern 分析器：结构摘要 + LLM 识别固定 GoF 清单（模式名/参与类/依据/置信度/是否误用）。
 */
@Component
public class DesignPatternAnalyzer implements Analyzer {

    private static final String SYSTEM_PROMPT = "你是资深 Java 架构专家，只输出合法 JSON，不要输出任何其他文字。";
    private static final String PATTERN_PROMPT = """
            请从下面这份「项目结构摘要」中识别设计模式，只关注以下常见模式：
            单例、工厂、抽象工厂、建造者、观察者、策略、装饰器、适配器、代理、模板方法。
            输出 JSON：
            {"patterns":[{"name":"模式名","participants":["参与类全限定名"],"evidence":"结构依据","confidence":"高|中|低","misused":false,"suggestion":"改进建议或空"}],"summary":"一句话概述"}
            若未识别到，patterns 为空数组。只输出这一个 JSON 对象，不要输出其他文字。

            结构摘要：
            %s
            """;

    private final MaterialService materialService;
    private final LlmClient llmClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DesignPatternAnalyzer(MaterialService materialService, LlmClient llmClient) {
        this.materialService = materialService;
        this.llmClient = llmClient;
    }

    @Override
    public int type() {
        return 3;
    }

    @Override
    public AnalyzeOutcome analyze(AnalysisContext ctx) throws Exception {
        Material material = materialService.prepare(ctx.project().getCredential(), ctx.project().getCredentialType(),
                ctx.ref(), ctx.branch(), ctx.record().getCommitSha(), ctx.scope());
        String userPrompt = String.format(PATTERN_PROMPT, material.structureSummary());
        if (ctx.customPrompt() != null && !ctx.customPrompt().isBlank()) {
            userPrompt += "\n关注点：\n" + ctx.customPrompt();
        }
        String content = llmClient.chatJson(ctx.baseUrl(), ctx.apiKey(), ctx.modelName(), SYSTEM_PROMPT, userPrompt);
        JsonNode result = objectMapper.readTree(content);
        return new AnalyzeOutcome(result, ReviewStatus.SUCCESS);
    }
}
