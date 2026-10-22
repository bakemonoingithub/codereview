package com.codereview.agent.api;

import java.util.List;

/** 列表支持页码与总数；下拉、选材及调用记录仍可使用游标。 */
public record PageResponse<T>(List<T> items, String nextCursor, boolean hasMore,
                              Long total, Integer pageNum, Integer pageSize) {
    public PageResponse(List<T> items, String nextCursor, boolean hasMore) {
        this(items, nextCursor, hasMore, null, null, null);
    }

    public <R> PageResponse<R> withItems(List<R> mapped) {
        return new PageResponse<>(mapped, nextCursor, hasMore, total, pageNum, pageSize);
    }
}
