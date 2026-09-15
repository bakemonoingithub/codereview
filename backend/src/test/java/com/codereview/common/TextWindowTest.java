package com.codereview.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文本窗口（文件查看的截断口径）。
 *
 * <p>为什么值得单独测：这里的"行数"与"字节数"两个口径很容易只做一半 ——
 * 只卡行数时，一行 5MB 的压缩 js 会原样发给浏览器；而按字节裁剪又极易把一个多字节字符劈成两半，
 * 结果界面上出现乱码。以下用例把这两条都钉住。
 */
class TextWindowTest {

    @Test
    void emptyInputIsZeroLines() {
        assertEquals(TextWindow.empty(), TextWindow.of(null, 1000, 1000));
        assertEquals(TextWindow.empty(), TextWindow.of("", 1000, 1000));
        // 只有一个换行符 = 一个空行
        assertEquals(1, TextWindow.of("\n", 1000, 1000).totalLines());
    }

    @Test
    void lineCountFollowsEditorConvention() {
        // "a\nb\n" 是 2 行，不是 3 行；"a\nb" 也是 2 行
        assertEquals(2, TextWindow.of("a\nb\n", 1000, 1000).totalLines());
        assertEquals(2, TextWindow.of("a\nb", 1000, 1000).totalLines());
        assertEquals(1, TextWindow.of("a", 1000, 1000).totalLines());
        assertEquals(1, TextWindow.of("a\n", 1000, 1000).totalLines());
    }

    @Test
    void contentUnderBothLimitsIsReturnedUntouched() {
        String text = "line1\nline2\nline3";

        TextWindow window = TextWindow.of(text, 1000, 1024 * 1024);

        assertEquals(text, window.content());
        assertFalse(window.truncated());
        assertEquals(3, window.totalLines());
    }

    @Test
    void truncatesAtLineLimitAndStillReportsTheRealTotal() {
        String text = lines(2500);

        TextWindow window = TextWindow.of(text, 1000, 1024 * 1024);

        assertEquals(1000, window.content().split("\n", -1).length);
        assertTrue(window.truncated());
        assertEquals(2500, window.totalLines(), "界面要说清“共 N 行”，所以总数必须是真实的");
    }

    @Test
    void exactLineLimitIsNotMarkedTruncated() {
        TextWindow window = TextWindow.of(lines(1000), 1000, 1024 * 1024);

        assertFalse(window.truncated(), "正好 1000 行不算被截断");
        assertEquals(1000, window.totalLines());
    }

    @Test
    void byteLimitCutsEvenWhenLineCountIsSmall() {
        // 单行 5MB：只卡行数完全拦不住
        String oneHugeLine = "x".repeat(5 * 1024 * 1024);

        TextWindow window = TextWindow.of(oneHugeLine, 1000, 2 * 1024 * 1024);

        assertTrue(window.truncated());
        assertTrue(window.contentBytes() <= 2 * 1024 * 1024, "实际 " + window.contentBytes());
        assertEquals(1, window.totalLines());
    }

    @Test
    void byteLimitCutsAtUtf8BoundaryWithoutMojibake() {
        // 每行 3 字节的中文，字节上限设为“刚好放不下整行”的位置
        String text = "中".repeat(100) + "\n" + "文".repeat(100);

        TextWindow window = TextWindow.of(text, 1000, 60);

        // 60 字节 = 20 个汉字，不能出现半个字符（半个字符会变成 U+FFFD）
        assertEquals(20, window.content().codePointCount(0, window.content().length()));
        assertFalse(window.content().contains("\uFFFD"), "不能把多字节字符劈开");
        assertTrue(window.truncated());
        assertEquals(2, window.totalLines());
    }

    @Test
    void unlimitedParametersKeepEverything() {
        String text = lines(3000);

        TextWindow window = TextWindow.of(text, 0, 0);

        assertEquals(text, window.content());
        assertFalse(window.truncated());
    }

    private static String lines(int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= count; i++) {
            sb.append("line-").append(i).append('\n');
        }
        return sb.toString();
    }
}
