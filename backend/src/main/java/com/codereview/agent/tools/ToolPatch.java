package com.codereview.agent.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从统一 diff 文本解析 hunk 头与两侧原始行范围。
 *
 * <p>行号必须来自 hunk 头：用 patch 行号顶替原始行号会让引用落到错误位置。
 * hunk 头缺省计数按 Git 规则视为 1；不依赖 {@code @@} 后面的函数说明。
 */
public final class ToolPatch {

    private static final Pattern HUNK = Pattern.compile(
            "^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@(.*)$");

    private ToolPatch() {
    }

    public static List<Hunk> parse(String patch) {
        List<Hunk> hunks = new ArrayList<>();
        if (patch == null || patch.isBlank()) {
            return hunks;
        }
        String[] lines = patch.split("\n", -1);
        for (String line : lines) {
            Matcher matcher = HUNK.matcher(line);
            if (!matcher.matches()) {
                continue;
            }
            hunks.add(new Hunk(hunks.size(),
                    matcher.group(5) == null ? "" : matcher.group(5).trim(),
                    Integer.parseInt(matcher.group(1)),
                    matcher.group(2) == null ? 1 : Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3)),
                    matcher.group(4) == null ? 1 : Integer.parseInt(matcher.group(4))));
        }
        return hunks;
    }

    /** 一个 hunk 的原始行范围；{@code beforeCount/afterCount} 为 0 表示该侧没有内容。 */
    public record Hunk(int index, String header, int beforeStart, int beforeCount,
                       int afterStart, int afterCount) {

        public boolean hasBefore() {
            return beforeCount > 0;
        }

        public boolean hasAfter() {
            return afterCount > 0;
        }

        public int beforeEnd() {
            return beforeCount == 0 ? beforeStart - 1 : beforeStart + beforeCount - 1;
        }

        public int afterEnd() {
            return afterCount == 0 ? afterStart - 1 : afterStart + afterCount - 1;
        }
    }
}
