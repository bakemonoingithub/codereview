package com.codereview.agent.tools;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次 Agent 任务允许读取的固定版本与比较关系。
 *
 * <p>模型只能使用这里列出的不透明标识（{@code V1…Vm}、{@code C1…Cn}）；
 * 原始 SHA 不进入工具参数。未列出的标识一律拒绝，读取范围因此不会扩展到其他版本或其他项目。
 */
public record AgentToolScope(String projectId, Map<String, String> versions,
                             Map<String, Comparison> comparisons) {

    public AgentToolScope {
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalArgumentException("工具作用域缺少项目");
        }
        versions = Map.copyOf(versions);
        comparisons = Map.copyOf(comparisons);
    }

    public static AgentToolScope of(String projectId, Map<String, String> versions) {
        return new AgentToolScope(projectId, versions, Map.of());
    }

    /** 单版本作用域，便于最小真实流程与测试。 */
    public static AgentToolScope single(String projectId, String sha) {
        return of(projectId, Map.of("V1", sha));
    }

    /** 某个版本的固定 SHA；不在本任务版本表内时返回 null。 */
    public String shaOf(String versionRef) {
        return versionRef == null ? null : versions.get(versionRef);
    }

    public Comparison comparison(String comparisonId) {
        return comparisonId == null ? null : comparisons.get(comparisonId);
    }

    /** 返回给模型的版本清单，按标识排序；只含本任务已冻结的版本。 */
    public List<ToolEnvelope.VersionRef> versionRefs() {
        List<ToolEnvelope.VersionRef> refs = new ArrayList<>();
        new ArrayList<>(versions.entrySet()).stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> refs.add(new ToolEnvelope.VersionRef(entry.getKey(), entry.getValue())));
        return List.copyOf(refs);
    }

    /** 测试与发起层构造比较关系的便捷写法。 */
    public static Map<String, Comparison> comparisonsOf(Comparison... comparisons) {
        Map<String, Comparison> map = new LinkedHashMap<>();
        for (Comparison comparison : comparisons) {
            map.put(comparison.id(), comparison);
        }
        return map;
    }

    /**
     * 比较关系：{@code headSha} 为固定提交，{@code baseSha} 为第一父提交；
     * 根提交的 base 为 null，读取时不伪造 SHA。
     */
    public record Comparison(String id, String headSha, String baseSha) {

        public Comparison {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("比较关系缺少标识");
            }
            if (headSha == null || !headSha.matches("[0-9a-fA-F]{40}")) {
                throw new IllegalArgumentException("比较关系缺少完整 head SHA");
            }
            if (baseSha != null && !baseSha.matches("[0-9a-fA-F]{40}")) {
                throw new IllegalArgumentException("比较关系的 base SHA 不是完整 SHA");
            }
        }

        public boolean root() {
            return baseSha == null;
        }
    }
}
