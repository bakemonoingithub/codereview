package com.codereview.common;

import java.nio.charset.StandardCharsets;

/**
 * 文本窗口：把一段文本裁到"够看"的规模，并如实报告裁了多少。
 *
 * <p>为什么要**行数与字节双上限**：行数是"给人看"的口径（前 1000 行足够判断这个文件是什么），
 * 字节才是"会不会把浏览器/网络打爆"的口径 —— 压缩过的 js/css、单行 json 可能一千行就到几十 MB。
 * 只卡行数等于没卡。
 *
 * <p>为什么返回 {@code totalLines} 而不是只返回截断标志：界面要能说清"已显示前 1000 行，共 N 行"，
 * 否则用户无法判断自己是看到了全文还是被截了。
 */
public record TextWindow(String content, boolean truncated, int totalLines) {

    /** 空文本：0 行、未截断。 */
    public static TextWindow empty() {
        return new TextWindow("", false, 0);
    }

    /**
     * 取文本的前 {@code maxLines} 行，并保证结果不超过 {@code maxBytes} 字节。
     *
     * @param text     原始文本（可为 null）
     * @param maxLines 最多保留多少行（&lt;=0 视为不限制行数）
     * @param maxBytes 最多保留多少 UTF-8 字节（&lt;=0 视为不限制字节数）
     */
    public static TextWindow of(String text, int maxLines, int maxBytes) {
        if (text == null || text.isEmpty()) {
            return empty();
        }
        String[] lines = text.split("\n", -1);
        int totalLines = countLines(text, lines.length);
        int limit = maxLines > 0 ? Math.min(maxLines, lines.length) : lines.length;
        // 与**逻辑行数**比较，而不是 split 出来的元素个数：末尾换行会多出一个空元素，
        // 拿它比会把"正好 1000 行"误判成被截断。
        boolean truncated = limit < totalLines;

        String kept = join(lines, limit);
        if (maxBytes > 0) {
            int bytes = kept.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > maxBytes) {
                truncated = true;
                kept = clipToBytes(kept, lines, limit, maxBytes);
            }
        }
        return new TextWindow(kept, truncated, totalLines);
    }

    /**
     * 行数口径：以换行符数量为准，末行无换行时再算一行。
     * {@code "a\nb\n"} 是 2 行（编辑器口径），不是 3 行 —— {@code split} 会多出一个空元素。
     */
    private static int countLines(String text, int splitLength) {
        return text.endsWith("\n") ? splitLength - 1 : splitLength;
    }

    private static String join(String[] lines, int limit) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < limit; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(lines[i]);
        }
        return sb.toString();
    }

    /** 字节超限时逐行回退；连第一行都超限就按字节裁这一行（UTF-8 边界安全）。 */
    private static String clipToBytes(String kept, String[] lines, int limit, int maxBytes) {
        StringBuilder sb = new StringBuilder();
        int used = 0;
        for (int i = 0; i < limit; i++) {
            String line = lines[i];
            int lineBytes = line.getBytes(StandardCharsets.UTF_8).length;
            int separator = i > 0 ? 1 : 0;
            if (used + separator + lineBytes > maxBytes) {
                int room = maxBytes - used - separator;
                if (room > 0) {
                    String head = clipLineToBytes(line, room);
                    if (!head.isEmpty()) {
                        if (i > 0) {
                            sb.append('\n');
                        }
                        sb.append(head);
                    }
                }
                break;
            }
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(line);
            used += separator + lineBytes;
        }
        return sb.toString();
    }

    /** 按 UTF-8 字节裁剪单行，按码点走以免把一个多字节字符劈成两半。 */
    private static String clipLineToBytes(String line, int maxBytes) {
        StringBuilder sb = new StringBuilder();
        int used = 0;
        for (int i = 0; i < line.length(); ) {
            int codePoint = line.codePointAt(i);
            int width = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8).length;
            if (used + width > maxBytes) {
                break;
            }
            sb.appendCodePoint(codePoint);
            used += width;
            i += Character.charCount(codePoint);
        }
        return sb.toString();
    }

    /** 供上层记录日志/拼提示用。 */
    public int contentBytes() {
        return content.getBytes(StandardCharsets.UTF_8).length;
    }
}
