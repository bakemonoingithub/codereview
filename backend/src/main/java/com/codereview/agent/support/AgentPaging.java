package com.codereview.agent.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.codereview.agent.api.ApiException;
import com.codereview.agent.api.ErrorCode;
import com.codereview.agent.api.PageResponse;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Function;

/**
 * 页码与游标分页的公共部分。
 *
 * <p>游标查询固定多取一条判断 hasMore；两种模式排序固定 {@code (created_at DESC, id DESC)}，
 * 与 {@link AgentCursor} 编码的键完全一致，避免"按时间分页却按 id 排序"的错位。
 */
public final class AgentPaging {

    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 100;

    private AgentPaging() {
    }

    public static int limit(Integer requested) {
        if (requested == null || requested <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(requested, MAX_LIMIT);
    }

    /** 两种分页共用筛选与排序；页码模式在数据库中计数和截取，不全量加载。 */
    public static <T> PageResponse<T> query(BaseMapper<T> mapper, LambdaQueryWrapper<T> wrapper,
                                           String cursor, Integer limit, Integer pageNum, Integer pageSize,
                                           SFunction<T, LocalDateTime> createdAt, SFunction<T, String> id) {
        if (pageNum == null && pageSize == null) {
            int size = limit(limit);
            applyCursor(wrapper, cursor, createdAt, id);
            applyOrder(wrapper, createdAt, id);
            wrapper.last("limit " + (size + 1));
            return assemble(mapper.selectList(wrapper), size, createdAt, id);
        }
        int requestedPage = pageNum == null ? 1 : pageNum;
        int size = pageSize == null ? DEFAULT_LIMIT : pageSize;
        if (requestedPage < 1 || (size != 10 && size != 20 && size != 50)
                || (cursor != null && !cursor.isBlank()) || limit != null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "页码须为正整数，每页条数只能为 10/20/50，不能与 cursor/limit 混用");
        }
        long total = mapper.selectCount(wrapper);
        long lastPage = Math.max(1, (total + size - 1) / size);
        int current = (int) Math.min(requestedPage, lastPage);
        long offset = (long) (current - 1) * size;
        applyOrder(wrapper, createdAt, id);
        wrapper.last("limit " + offset + ", " + size);
        List<T> items = total == 0 ? List.of() : mapper.selectList(wrapper);
        return new PageResponse<>(items, null, offset + size < total, total, current, size);
    }

    /** 在已有条件之后追加游标条件，不覆盖调用方已有的过滤。 */
    public static <T> void applyCursor(LambdaQueryWrapper<T> wrapper, String cursor,
                                       SFunction<T, LocalDateTime> createdAt, SFunction<T, String> id) {
        AgentCursor.CursorValue value = AgentCursor.decode(cursor);
        if (value == null) {
            return;
        }
        LocalDateTime since = AgentCursor.toLocalDateTime(value.createdAtMillis());
        wrapper.and(inner -> inner.lt(createdAt, since)
                .or(same -> same.eq(createdAt, since).lt(id, value.id())));
    }

    public static <T> void applyOrder(LambdaQueryWrapper<T> wrapper,
                                      SFunction<T, LocalDateTime> createdAt, SFunction<T, String> id) {
        wrapper.orderByDesc(createdAt).orderByDesc(id);
    }

    /** rows 需按 limit + 1 查询；多出来的那条只用于判断 hasMore。 */
    public static <T> PageResponse<T> assemble(List<T> rows, int limit,
                                               Function<T, LocalDateTime> createdAt, Function<T, String> id) {
        boolean hasMore = rows.size() > limit;
        List<T> items = hasMore ? List.copyOf(rows.subList(0, limit)) : List.copyOf(rows);
        String nextCursor = null;
        if (hasMore && !items.isEmpty()) {
            T last = items.get(items.size() - 1);
            nextCursor = AgentCursor.encode(createdAt.apply(last), id.apply(last));
        }
        return new PageResponse<>(items, nextCursor, hasMore);
    }
}
