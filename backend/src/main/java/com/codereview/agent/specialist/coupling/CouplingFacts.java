package com.codereview.agent.specialist.coupling;

import com.codereview.agent.prompt.PromptFact;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 目标文件的文件级依赖事实。
 *
 * <p>指标先由程序计算，模型只解释这些事实。{@code resolvedOutgoingCount} 是可靠定位到
 * 仓库内其他文件的不同类型数；{@code unresolvedTypeCount} 是未能可靠定位的类型数。
 * 不提供未全仓计算的 fan-in、instability 或模块得分，也不把“未解析”当成“没有依赖”。
 */
public record CouplingFacts(List<Edge> edges, int resolvedOutgoingCount, int unresolvedTypeCount,
                            Map<String, Integer> declarationRelationCounts, double coverage,
                            String sha, String side, List<String> limitations) {

    public CouplingFacts {
        edges = edges == null ? List.of() : List.copyOf(edges);
        declarationRelationCounts = declarationRelationCounts == null
                ? Map.of() : Map.copyOf(declarationRelationCounts);
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
    }

    public Set<String> dependencyIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (Edge edge : edges) {
            ids.add(edge.id());
        }
        return ids;
    }

    public List<PromptFact> promptFacts() {
        return edges.stream()
                .map(edge -> new PromptFact(edge.id(), edge.describe()))
                .toList();
    }

    public record Edge(String id, String sourceType, String targetType, String targetPath,
                       String relation, int line) {

        public String describe() {
            return "[" + relation + "] " + sourceType + " -> " + targetType
                    + "（" + targetPath + ":" + line + "）";
        }
    }
}
