package com.codereview.common;

/**
 * 分页参数约束。
 *
 * 《后端约定》写着"默认 10、上限 100（超限按上限截断）"，但 MyBatis-Plus 的
 * {@code PaginationInnerInterceptor} 并未设置 {@code maxLimit} —— 不在这里兜住，
 * 调用方给 {@code pageSize=100000} 就会真的去查十万行。
 *
 * 目前只有审查记录列表接了这个约束（本次需求范围内）；其余分页接口是否统一接入见
 * {@code dev/待办backlog.md}。
 */
public final class PageLimits {

    /** 单页最大条数（《后端约定》上限） */
    public static final long MAX_PAGE_SIZE = 100;

    private PageLimits() {
    }

    /** 超限截断到 {@link #MAX_PAGE_SIZE}，非正数回落到 1（避免 LIMIT 0 查不出数据） */
    public static long clampPageSize(long pageSize) {
        if (pageSize < 1) {
            return 1;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }

    /** 页码从 1 起，非正数回落到 1 */
    public static long clampPageNum(long pageNum) {
        return pageNum < 1 ? 1 : pageNum;
    }
}
