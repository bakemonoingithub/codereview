package com.codereview.diff;

/**
 * 一个 diff 审查单元（送给 LLM 的最小单位）。
 *
 * @param path       文件路径
 * @param kind       {@code diff-method} 方法级 / {@code diff-hunk} 原始 hunk 退化 / {@code full-file} 全文件兜底
 * @param name       方法名或文件名
 * @param startLine  新侧起始行
 * @param endLine    新侧结束行
 * @param changeType added / modified / removed / renamed
 * @param truncated  方法体是否因超长被窗口截断
 * @param note       退化说明（如"未做方法级补全"），无则 null
 * @param text       渲染好的代码块（含新侧行号与 +/- 标记）
 */
public record DiffReviewUnit(
        String path,
        String kind,
        String name,
        int startLine,
        int endLine,
        String changeType,
        boolean truncated,
        String note,
        String text) {

    /** 注入提示词用的固定头。 */
    public String header() {
        return String.format("{文件路径:%s, 类型:%s, 名称:%s, 行范围:%d-%d}",
                path, kind, name, startLine, endLine);
    }
}
