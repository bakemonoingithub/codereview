package com.codereview.agent.prompt;

/**
 * 服务端计算出的只读事实（当前用于耦合指标）。
 *
 * <p>模型只能引用 {@code id}，不能改写或生成新的依赖事实；文本由服务端格式化，
 * 不放进代码材料段，避免与源码行号混淆。
 */
public record PromptFact(String id, String text) {

    public PromptFact {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("事实缺少稳定编号");
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("事实缺少说明：" + id);
        }
    }
}
