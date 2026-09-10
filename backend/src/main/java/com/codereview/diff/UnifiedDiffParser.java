package com.codereview.diff;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * unified diff 解析（GitHub / Gitea 的 {@code files[].patch} 就是这种文本）。
 * <p>
 * 只关心 {@code @@ -old,count +new,count @@} 及其后的增删改行；{@code diff --git} / {@code index}
 * / {@code ---} / {@code +++} / {@code \ No newline at end of file} 等头部与标记一律跳过。
 */
public final class UnifiedDiffParser {

    private static final Pattern HUNK_HEADER =
            Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*$");

    public enum LineType { CONTEXT, ADDED, REMOVED }

    /** 一行：{@code oldLine} 为旧侧行号（新增行为 null），{@code newLine} 为新侧行号（删除行为 null）。 */
    public record Line(LineType type, Integer oldLine, Integer newLine, String text) {
    }

    /** 一个 hunk。 */
    public record Hunk(int oldStart, int oldCount, int newStart, int newCount, String header, List<Line> lines) {

        /** 新侧覆盖的末行（count 为 0 时即 newStart）。 */
        public int newEndLine() {
            return newCount <= 0 ? newStart : newStart + newCount - 1;
        }

        /** 该 hunk 内 New 侧涉及的全部行号（上下文行 + 新增行）。 */
        public List<Integer> newSideLines() {
            List<Integer> result = new ArrayList<>();
            for (Line l : lines) {
                if (l.newLine() != null) {
                    result.add(l.newLine());
                }
            }
            return result;
        }

        /** 该 hunk 内新增行的新侧行号。 */
        public List<Integer> addedNewLines() {
            List<Integer> result = new ArrayList<>();
            for (Line l : lines) {
                if (l.type() == LineType.ADDED && l.newLine() != null) {
                    result.add(l.newLine());
                }
            }
            return result;
        }
    }

    private UnifiedDiffParser() {
    }

    public static List<Hunk> parse(String patch) {
        List<Hunk> hunks = new ArrayList<>();
        if (patch == null || patch.isBlank()) {
            return hunks;
        }
        HunkBuilder current = null;
        String[] rawLines = patch.split("\n", -1);
        // patch 以换行结尾时 split 会多出一个空串，它不是 diff 内容，直接丢弃
        int limit = rawLines.length;
        if (limit > 0 && rawLines[limit - 1].isEmpty()) {
            limit--;
        }
        for (int i = 0; i < limit; i++) {
            String raw = rawLines[i];
            Matcher m = HUNK_HEADER.matcher(raw);
            if (m.matches()) {
                if (current != null) {
                    hunks.add(current.build());
                }
                current = new HunkBuilder(
                        Integer.parseInt(m.group(1)),
                        m.group(2) == null ? 1 : Integer.parseInt(m.group(2)),
                        Integer.parseInt(m.group(3)),
                        m.group(4) == null ? 1 : Integer.parseInt(m.group(4)),
                        raw);
                continue;
            }
            if (current == null) {
                continue;
            }
            if (raw.startsWith("\\")) {
                continue;
            }
            char marker = raw.isEmpty() ? ' ' : raw.charAt(0);
            String text = raw.isEmpty() ? "" : raw.substring(1);
            switch (marker) {
                case '+' -> current.add(LineType.ADDED, text);
                case '-' -> current.add(LineType.REMOVED, text);
                case ' ' -> current.add(LineType.CONTEXT, text);
                default -> {
                    // 空行在 hunk 内既可能是上下文行也可能无标记，按上下文处理
                    if (raw.isEmpty()) {
                        current.add(LineType.CONTEXT, "");
                    }
                }
            }
        }
        if (current != null) {
            hunks.add(current.build());
        }
        return hunks;
    }

    private static final class HunkBuilder {
        private final int oldStart;
        private final int oldCount;
        private final int newStart;
        private final int newCount;
        private final String header;
        private final List<Line> lines = new ArrayList<>();
        private int oldCursor;
        private int newCursor;

        HunkBuilder(int oldStart, int oldCount, int newStart, int newCount, String header) {
            this.oldStart = oldStart;
            this.oldCount = oldCount;
            this.newStart = newStart;
            this.newCount = newCount;
            this.header = header;
            this.oldCursor = oldStart;
            this.newCursor = newStart;
        }

        void add(LineType type, String text) {
            switch (type) {
                case CONTEXT -> lines.add(new Line(type, oldCursor++, newCursor++, text));
                case ADDED -> lines.add(new Line(type, null, newCursor++, text));
                case REMOVED -> lines.add(new Line(type, oldCursor++, null, text));
            }
        }

        Hunk build() {
            return new Hunk(oldStart, oldCount, newStart, newCount, header, List.copyOf(lines));
        }
    }
}
