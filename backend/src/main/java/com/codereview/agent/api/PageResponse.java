package com.codereview.agent.api;

import java.util.List;

/** 游标分页响应：不返回总数，客户端按 nextCursor 加载更多。 */
public record PageResponse<T>(List<T> items, String nextCursor, boolean hasMore) {
}
