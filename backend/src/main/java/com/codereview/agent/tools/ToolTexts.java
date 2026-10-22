package com.codereview.agent.tools;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 工具结果里的行处理与摘要；行号一律是文件原始行号，截短必须显式标记。 */
public final class ToolTexts {

    private ToolTexts() {
    }

    /** 与读取入口同一口径：去掉末尾换行后按 \n 切分，空文件返回 0 行。 */
    public static String[] splitLines(String content) {
        if (content == null || content.isEmpty()) {
            return new String[0];
        }
        String normalized = content.endsWith("\n") ? content.substring(0, content.length() - 1) : content;
        return normalized.split("\n", -1);
    }

    /** 取 {@code startLine..endLine}（1 基、含端点）的原始行；越界自动夹到文件范围。 */
    public static String joinLines(String[] lines, int startLine, int endLine) {
        int from = Math.max(1, startLine);
        int to = Math.min(lines.length, endLine);
        if (from > to) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (int line = from; line <= to; line++) {
            if (line > from) {
                text.append('\n');
            }
            text.append(lines[line - 1]);
        }
        return text.toString();
    }

    public static String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    public static int utf8Bytes(String text) {
        return text == null ? 0 : text.getBytes(StandardCharsets.UTF_8).length;
    }

    /** 单行显示上限；超出时保留开头并标明原始长度，不悄悄丢内容。 */
    public static String truncateLine(String line, int maxChars) {
        if (line == null) {
            return "";
        }
        if (line.length() <= maxChars) {
            return line;
        }
        return line.substring(0, maxChars) + "…[截短，原长 " + line.length() + " 字符]";
    }

    /**
     * 把文本夹到字节上限内，按字符边界截断并追加显式标记。
     *
     * <p>只用于"单行本身就超过材料上限"的病态情况；正常材料按行分页，不走这里。
     */
    public static String clampToBytes(String text, int maxBytes) {
        if (text == null || utf8Bytes(text) <= maxBytes) {
            return text;
        }
        String marker = "…[已截短到 " + maxBytes + " 字节]";
        int budget = maxBytes - utf8Bytes(marker);
        StringBuilder builder = new StringBuilder();
        int bytes = 0;
        for (int index = 0; index < text.length(); ) {
            int codePoint = text.codePointAt(index);
            String piece = new String(Character.toChars(codePoint));
            int pieceBytes = utf8Bytes(piece);
            if (bytes + pieceBytes > budget) {
                break;
            }
            builder.append(piece);
            bytes += pieceBytes;
            index += Character.charCount(codePoint);
        }
        return builder.append(marker).toString();
    }
}
