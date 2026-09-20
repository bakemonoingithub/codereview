package com.codereview.analyzer;

import com.codereview.llm.LlmClient;
import com.codereview.material.DepEdge;
import com.codereview.material.DepGraph;
import com.codereview.material.Material;
import com.codereview.material.MaterialService;
import com.codereview.material.ModuleGraph;
import com.codereview.review.ReviewStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * coupling 分析器：依赖图确定性计算（扇入/扇出/循环依赖/高耦合）+ LLM 解读修复建议。
 * 确定性结论优先，LLM 解读为 best-effort（失败不影响确定性结果）。
 */
@Component
public class CouplingAnalyzer implements Analyzer {

    private static final int DEFAULT_THRESHOLD = 10;

    private static final String SYSTEM_PROMPT = "你是资深 Java 架构审查专家，只输出合法 JSON，不要输出任何其他文字。";
    private static final String SYSTEM_PROMPT_FREE = "你是资深 Java 架构审查专家。";
    private static final String SUGGEST_PROMPT = """
            请基于下面的确定性依赖分析结论，给出修复建议。
            输出 JSON：{"suggestions":[{"target":"类全限定名","issue":"问题","suggestion":"建议","severity":"MAJOR|MINOR"}],"summary":"一句话概述"}。
            若无改进点，suggestions 为空数组。只输出 JSON，不要输出其他文字。

            确定性结论：
            """;

    private final MaterialService materialService;
    private final LlmClient llmClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CouplingAnalyzer(MaterialService materialService, LlmClient llmClient) {
        this.materialService = materialService;
        this.llmClient = llmClient;
    }

    @Override
    public int type() {
        return 2;
    }

    @Override
    public AnalyzeOutcome analyze(AnalysisContext ctx) {
        Material material = materialService.prepare(ctx.project().getCredential(), ctx.project().getCredentialType(),
                ctx.ref(), ctx.branch(), ctx.record().getCommitSha(), ctx.scope());
        int threshold = ctx.params() == null ? DEFAULT_THRESHOLD : ctx.params().path("threshold").asInt(DEFAULT_THRESHOLD);
        ObjectNode result = buildDeterministic(material, threshold, objectMapper);
        try {
            if (ctx.customPrompt() != null && !ctx.customPrompt().isBlank()) {
                String raw = llmClient.chat(ctx.baseUrl(), ctx.apiKey(), ctx.modelName(), SYSTEM_PROMPT_FREE,
                        ctx.customPrompt() + "\n\n确定性结论：\n" + result.toPrettyString());
                result.put("raw", raw);
                result.set("suggestions", objectMapper.createArrayNode());
            } else {
                JsonNode suggestions = llmSuggest(ctx, result);
                result.set("suggestions", suggestions.path("suggestions"));
                String llmSummary = suggestions.path("summary").asText("");
                if (!llmSummary.isBlank()) {
                    result.put("summary", llmSummary);
                }
            }
        } catch (Exception e) {
            result.set("suggestions", objectMapper.createArrayNode());
            result.put("summary", result.path("summary").asText() + "（LLM 解读失败，仅确定性结论）");
        }
        return new AnalyzeOutcome(result, ReviewStatus.SUCCESS);
    }

    /**
     * 确定性耦合结论（纯逻辑，可单测）。
     *
     * <p>结果里**类级字段全部保留**（向后兼容：前端图、既有报告都还在读），T-03 只在后面追加模块级字段
     * （{@code moduleSummary / modules / moduleEdges / moduleCycles / moduleMutualPairs}），
     * 并把 {@code summary} 改成"模块在前、类级在后"。{@code moduleSummary} 是确定性的，
     * 后面 LLM 返回 summary 时只会覆盖 {@code summary} 而碰不到它。
     */
    static ObjectNode buildDeterministic(Material material, int threshold, ObjectMapper mapper) {
        DepGraph g = new DepGraph(material);
        ModuleGraph modules = ModuleGraph.of(material);
        ObjectNode root = mapper.createObjectNode();
        ArrayNode nodes = root.putArray("nodes");
        for (String fqcn : g.nodes()) {
            ObjectNode n = nodes.addObject();
            n.put("id", fqcn);
            n.put("label", shortName(fqcn));
            n.put("fanIn", g.fanIn(fqcn));
            n.put("fanOut", g.fanOut(fqcn));
            n.put("high", g.fanOut(fqcn) > threshold);
        }
        List<List<String>> cycles = g.findCycles();
        Set<String> inCycle = new LinkedHashSet<>();
        ArrayNode cycleArr = root.putArray("cycles");
        for (List<String> cyc : cycles) {
            ArrayNode c = cycleArr.addArray();
            cyc.forEach(c::add);
            inCycle.addAll(cyc);
        }
        for (JsonNode n : nodes) {
            ((ObjectNode) n).put("inCycle", inCycle.contains(n.path("id").asText()));
        }
        ArrayNode edges = root.putArray("edges");
        for (DepEdge e : material.edges()) {
            ObjectNode edge = edges.addObject();
            edge.put("source", e.from());
            edge.put("target", e.to());
        }
        ArrayNode highArr = root.putArray("highCoupling");
        for (String fqcn : g.highCoupling(threshold)) {
            highArr.add(fqcn);
        }

        // ---- T-03：模块级（包级）聚合，确定性结论 ----
        root.put("moduleSummary", modules.summary());
        ArrayNode moduleArr = root.putArray("modules");
        for (ModuleGraph.Module m : modules.modules()) {
            ObjectNode n = moduleArr.addObject();
            n.put("name", m.name());
            n.put("classCount", m.classCount());
            n.put("ca", m.ca());
            n.put("ce", m.ce());
            n.put("instability", m.instability());
            n.put("high", m.high());
        }
        ArrayNode moduleEdgeArr = root.putArray("moduleEdges");
        for (ModuleGraph.ModuleEdge e : modules.edges()) {
            ObjectNode edge = moduleEdgeArr.addObject();
            edge.put("from", e.from());
            edge.put("to", e.to());
            edge.put("weight", e.weight());
        }
        ArrayNode moduleCycleArr = root.putArray("moduleCycles");
        for (List<String> cyc : modules.cycles()) {
            ArrayNode c = moduleCycleArr.addArray();
            cyc.forEach(c::add);
        }
        ArrayNode mutualArr = root.putArray("moduleMutualPairs");
        for (List<String> pair : modules.mutualPairs()) {
            ArrayNode p = mutualArr.addArray();
            pair.forEach(p::add);
        }

        root.put("summary", String.format("%s；类级：依赖图 %d 节点 / %d 边；高耦合 %d 个；循环依赖 %d 组",
                modules.summary(), g.nodes().size(), material.edges().size(), highArr.size(), cycles.size()));
        return root;
    }

    private JsonNode llmSuggest(AnalysisContext ctx, ObjectNode deterministic) throws Exception {
        String userPrompt = SUGGEST_PROMPT + deterministic.toPrettyString();
        String content = llmClient.chatJson(ctx.baseUrl(), ctx.apiKey(), ctx.modelName(), SYSTEM_PROMPT, userPrompt);
        return objectMapper.readTree(content);
    }

    private static String shortName(String fqcn) {
        int i = fqcn.lastIndexOf('.');
        return i >= 0 ? fqcn.substring(i + 1) : fqcn;
    }
}
