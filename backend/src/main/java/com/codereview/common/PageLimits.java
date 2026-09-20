package com.codereview.common;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

/**
 * 分页参数约束。
 *
 * 《后端约定》写着"默认 10、上限 100（超限按上限截断）"，但 MyBatis-Plus 的
 * {@code PaginationInnerInterceptor} 并未设置 {@code maxLimit} —— 不在这里兜住，
 * 调用方给 {@code pageSize=100000} 就会真的去查十万行。
 *
 * <p><b>两道防线，一处口径</b>：
 * <ol>
 *   <li>各列表接口用 {@link #page(long, long)} 构造分页对象（显式、可单测）；</li>
 *   <li>{@code MybatisPlusConfig} 给分页插件设 {@code maxLimit = }{@link #MAX_PAGE_SIZE}
 *       —— 以后新增的接口即使忘了用 {@link #page(long, long)}，也钳得住。</li>
 * </ol>
 */
public final class PageLimits {

    /** 单页最大条数（《后端约定》上限） */
    public static final long MAX_PAGE_SIZE = 100;

    private PageLimits() {
    }

    /**
     * 统一的分页对象工厂：六个列表接口都走它，避免"只 clamp 了其中一个参数"。
     *
     * <p>与分页插件的 {@code maxLimit} 用的是同一个上限常量（见 {@code MybatisPlusConfig}），
     * 所以返回体里的 {@code size} 就是实际生效值，前端据此算总页数不会出现空页。
     */
    public static <T> Page<T> page(long pageNum, long pageSize) {
        return new Page<>(clampPageNum(pageNum), clampPageSize(pageSize));
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
