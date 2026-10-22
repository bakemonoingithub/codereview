package com.codereview.agent.tools;

import java.util.List;

/**
 * 一次已校验的工具调用参数。
 *
 * <p>{@code fingerprint} 把本次查询的规范化条件压成一个稳定摘要，游标只对同一查询有效；
 * 模型换查询却复用旧游标时会被拒绝，不能借游标读取别的范围。
 */
public record ToolInvocation(String tool, String versionRef, String sha, String comparisonId,
                             String path, Integer depth, int limit, String cursor, String glob,
                             String query, List<String> extensions, Integer start, Integer end) {

    public ToolInvocation {
        extensions = extensions == null ? List.of() : List.copyOf(extensions);
    }

    public String fingerprint() {
        return String.join("|",
                tool,
                nz(versionRef),
                nz(comparisonId),
                nz(path),
                depth == null ? "-" : depth.toString(),
                Integer.toString(limit),
                nz(glob),
                nz(query),
                String.join(",", extensions.stream().sorted().toList()),
                start == null ? "-" : start.toString(),
                end == null ? "-" : end.toString());
    }

    private static String nz(String value) {
        return value == null ? "-" : value;
    }
}
