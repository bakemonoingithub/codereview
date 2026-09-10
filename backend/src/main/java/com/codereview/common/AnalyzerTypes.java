package com.codereview.common;

/**
 * 分析器类型常量与判定。
 * <p>
 * 集中定义是为了避免同一上限散落在多处——历史上「策略创建」与「审查触发」各写了一份 {@code > 4}，
 * 新增 diff-review 时只改了一处，直接导致"策略建不出来、提示分析器类型不支持"。
 */
public final class AnalyzerTypes {

    public static final int LLM_REVIEW = 1;
    public static final int COUPLING = 2;
    public static final int DESIGN_PATTERN = 3;
    public static final int API_REVIEW = 4;
    public static final int DIFF_REVIEW = 5;

    public static final int MIN = LLM_REVIEW;
    public static final int MAX = DIFF_REVIEW;

    private AnalyzerTypes() {
    }

    public static boolean isValid(Integer type) {
        return type != null && type >= MIN && type <= MAX;
    }

    /** 除 api-review（走外部 API）外，其余分析器都需要模型配置 */
    public static boolean requiresModel(Integer type) {
        return type != null && type != API_REVIEW;
    }

    /** diff-review 需要把审查锚定到具体提交 */
    public static boolean requiresCommitSha(Integer type) {
        return type != null && type == DIFF_REVIEW;
    }
}
