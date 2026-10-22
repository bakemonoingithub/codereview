package com.codereview.agent.tools;

import java.util.List;

/**
 * 工具返回的通用 envelope。
 *
 * <p>{@code completeness} 表示请求范围的完成程度，{@code status} 表示这次调用本身是否成功，
 * 两者不能互相替代：只有 {@code OK + COMPLETE + 空命中} 才能说明查询没有匹配；
 * 超时、截断与读取失败都必须保留为非 COMPLETE。
 */
public record ToolEnvelope(String toolCallId, String status, List<VersionRef> versionRefs,
                           String comparisonId, List<ToolMaterial> materials, Object data,
                           String completeness, String nextCursor, String diagnostic) {

    public static final String OK = "OK";
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String UNREADABLE = "UNREADABLE";
    public static final String INVALID_ARGUMENT = "INVALID_ARGUMENT";
    public static final String TIMEOUT = "TIMEOUT";
    public static final String ERROR = "ERROR";

    public static final String COMPLETE = "COMPLETE";
    public static final String PARTIAL = "PARTIAL";
    public static final String UNKNOWN = "UNKNOWN";

    public ToolEnvelope {
        materials = materials == null ? List.of() : List.copyOf(materials);
        versionRefs = versionRefs == null ? List.of() : List.copyOf(versionRefs);
    }

    public boolean ok() {
        return OK.equals(status);
    }

    public boolean complete() {
        return COMPLETE.equals(completeness);
    }

    /** 版本清单项；{@code sha} 是阅读与引用的真实身份。 */
    public record VersionRef(String version, String sha) {
    }

    /** 一条可引用材料；{@code side=BEFORE/AFTER} 只用于差异两侧。 */
    public record ToolMaterial(String materialId, String path, String side, String sha,
                               Integer startLine, Integer endLine, String contentHash,
                               String completeness, String content) {
    }

    /** 失败结果的统一构造：不携带材料，也不声称任何范围完整。 */
    public static ToolEnvelope failure(String toolCallId, ToolFailure failure, AgentToolScope scope) {
        return new ToolEnvelope(toolCallId, failure.status(), scope == null ? List.of()
                : scope.versionRefs(), null, List.of(), null, UNKNOWN, null, failure.getMessage());
    }
}
