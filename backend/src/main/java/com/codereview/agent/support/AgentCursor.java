package com.codereview.agent.support;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;

/**
 * 游标：{@code base64url(createdAtMillis + ":" + id)}。
 *
 * <p>服务端只保证"能解码并带出毫秒与 id"，不解释客户端构造的语义；
 * 排序固定 {@code (created_at DESC, id DESC)}，因此同毫秒内的记录靠 ULID 定序。
 */
public final class AgentCursor {

    private AgentCursor() {
    }

    public static String encode(LocalDateTime createdAt, String id) {
        long millis = createdAt.toInstant(ZoneOffset.UTC).toEpochMilli();
        String raw = millis + ":" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static CursorValue decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        String raw;
        try {
            raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("游标格式无效", e);
        }
        int separator = raw.indexOf(':');
        if (separator <= 0) {
            throw new IllegalArgumentException("游标缺少时间或 ID");
        }
        long millis;
        try {
            millis = Long.parseLong(raw.substring(0, separator));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("游标时间无效", e);
        }
        String id = raw.substring(separator + 1);
        if (!AgentIds.isValid(id)) {
            throw new IllegalArgumentException("游标中的 ID 无效");
        }
        return new CursorValue(millis, id);
    }

    public static LocalDateTime toLocalDateTime(long millis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneOffset.UTC);
    }

    public record CursorValue(long createdAtMillis, String id) {
    }
}
