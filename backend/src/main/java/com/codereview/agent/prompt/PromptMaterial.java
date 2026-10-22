package com.codereview.agent.prompt;

/**
 * 一段进入请求的材料。
 *
 * <p>{@code materialId} 是调用内稳定编号，模型只能引用它；仓库、SHA、路径和侧由服务端
 * 在保存结果时补齐，模型不能自造。行号是原文件行号，不是片段内偏移。
 */
public record PromptMaterial(String materialId, String path, String sha, String side,
                             Integer startLine, Integer endLine, String content,
                             String completeness, String diagnostic) {

    public PromptMaterial {
        if (materialId == null || materialId.isBlank()) {
            throw new IllegalArgumentException("材料缺少稳定编号");
        }
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("材料缺少路径：" + materialId);
        }
        if (content == null) {
            throw new IllegalArgumentException("材料缺少正文：" + materialId);
        }
        if (startLine != null && startLine < 1) {
            throw new IllegalArgumentException("材料起始行必须从 1 开始：" + materialId);
        }
        if (startLine != null && endLine != null && endLine < startLine) {
            throw new IllegalArgumentException("材料结束行小于起始行：" + materialId);
        }
    }

    public boolean incomplete() {
        return completeness != null && !"COMPLETE".equals(completeness);
    }
}
