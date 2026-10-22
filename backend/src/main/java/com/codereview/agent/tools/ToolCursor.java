package com.codereview.agent.tools;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 工具游标：{@code base64url("v1|工具|查询指纹|偏移")}。
 *
 * <p>游标里没有任务、项目或 SHA，只保留查询指纹；查询指纹由规范化参数算出，
 * 所以换查询、换版本或换 schema 后旧游标一律失效，不能借它读取别的范围。
 */
public record ToolCursor(String tool, String fingerprint, int offset) {

    private static final String PREFIX = "v1";

    public static String encode(ToolInvocation invocation, int offset) {
        String raw = PREFIX + "|" + invocation.tool() + "|" + fingerprintOf(invocation) + "|" + offset;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static ToolCursor decode(String cursor, ToolInvocation invocation) {
        if (cursor == null || cursor.isBlank()) {
            return new ToolCursor(invocation.tool(), fingerprintOf(invocation), 0);
        }
        String raw;
        try {
            raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw ToolFailure.invalid("CURSOR_INVALID", "游标格式无效");
        }
        String[] parts = raw.split("\\|", -1);
        if (parts.length != 4 || !PREFIX.equals(parts[0])) {
            throw ToolFailure.invalid("CURSOR_INVALID", "游标格式无效");
        }
        if (!invocation.tool().equals(parts[1]) || !fingerprintOf(invocation).equals(parts[2])) {
            throw ToolFailure.invalid("CURSOR_MISMATCH", "游标不属于本次查询，请从第一页重新开始");
        }
        int offset;
        try {
            offset = Integer.parseInt(parts[3]);
        } catch (NumberFormatException e) {
            throw ToolFailure.invalid("CURSOR_INVALID", "游标偏移无效");
        }
        if (offset < 0) {
            throw ToolFailure.invalid("CURSOR_INVALID", "游标偏移无效");
        }
        return new ToolCursor(invocation.tool(), parts[2], offset);
    }

    static String fingerprintOf(ToolInvocation invocation) {
        return sha256(invocation.fingerprint()).substring(0, 16);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
