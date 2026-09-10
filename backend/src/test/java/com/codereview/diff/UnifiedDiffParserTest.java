package com.codereview.diff;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnifiedDiffParserTest {

    private static final String PATCH =
            "@@ -1,6 +1,7 @@\n"
                    + " public class A {\n"
                    + "     public void m() {\n"
                    + "         int a = 1;\n"
                    + "+        int b = 2;\n"
                    + "         int c = 3;\n"
                    + "     }\n"
                    + " }\n";

    @Test
    void parsesSingleHunkWithCountsAndLineNumbers() {
        List<UnifiedDiffParser.Hunk> hunks = UnifiedDiffParser.parse(PATCH);

        assertEquals(1, hunks.size());
        UnifiedDiffParser.Hunk h = hunks.get(0);
        assertEquals(1, h.oldStart());
        assertEquals(6, h.oldCount());
        assertEquals(1, h.newStart());
        assertEquals(7, h.newCount());
        assertEquals(7, h.newEndLine());
        assertEquals(List.of(4), h.addedNewLines());
        assertEquals(7, h.newSideLines().size(), "上下文行 + 新增行都应有新侧行号");
    }

    @Test
    void skipsGitHeadersAndNoNewlineMarker() {
        String patch = "diff --git a/A.java b/A.java\n"
                + "index 111..222 100644\n"
                + "--- a/A.java\n"
                + "+++ b/A.java\n"
                + "@@ -1,2 +1,2 @@\n"
                + "-old\n"
                + "+new\n"
                + " tail\n"
                + "\\ No newline at end of file\n";

        List<UnifiedDiffParser.Hunk> hunks = UnifiedDiffParser.parse(patch);

        assertEquals(1, hunks.size(), "diff --git / index / --- / +++ 不应产生 hunk");
        assertEquals(3, hunks.get(0).lines().size(), "\\ No newline 标记应被跳过，只留 -old/+new/ tail 三行");
        assertEquals(UnifiedDiffParser.LineType.REMOVED, hunks.get(0).lines().get(0).type());
        assertEquals(UnifiedDiffParser.LineType.ADDED, hunks.get(0).lines().get(1).type());
        assertEquals(UnifiedDiffParser.LineType.CONTEXT, hunks.get(0).lines().get(2).type());
    }

    @Test
    void removedLineHasOldNumberOnlyAndAddedHasNewNumberOnly() {
        UnifiedDiffParser.Hunk h = UnifiedDiffParser.parse(PATCH).get(0);

        UnifiedDiffParser.Line added = h.lines().stream()
                .filter(l -> l.type() == UnifiedDiffParser.LineType.ADDED).findFirst().orElseThrow();
        assertEquals(4, added.newLine().intValue());
        assertEquals(null, added.oldLine(), "新增行没有旧侧行号");

        UnifiedDiffParser.Line context = h.lines().stream()
                .filter(l -> l.type() == UnifiedDiffParser.LineType.CONTEXT).findFirst().orElseThrow();
        assertEquals(1, context.oldLine().intValue());
        assertEquals(1, context.newLine().intValue());
    }

    @Test
    void parsesMultipleHunks() {
        String patch = "@@ -1,2 +1,2 @@\n-a\n+b\n c\n@@ -10,2 +10,3 @@\n d\n+e\n f\n";

        List<UnifiedDiffParser.Hunk> hunks = UnifiedDiffParser.parse(patch);

        assertEquals(2, hunks.size());
        assertEquals(10, hunks.get(1).newStart());
        assertEquals(List.of(11), hunks.get(1).addedNewLines());
    }

    @Test
    void emptyOrNullPatchYieldsNoHunks() {
        assertTrue(UnifiedDiffParser.parse(null).isEmpty());
        assertTrue(UnifiedDiffParser.parse("").isEmpty());
        assertTrue(UnifiedDiffParser.parse("   ").isEmpty());
        assertTrue(UnifiedDiffParser.parse("no hunk header here\n").isEmpty());
    }

    @Test
    void deletionOnlyHunkHasNoNewSideLines() {
        String patch = "@@ -1,3 +1,2 @@\n a\n-b\n c\n";

        UnifiedDiffParser.Hunk h = UnifiedDiffParser.parse(patch).get(0);

        assertEquals(0, h.addedNewLines().size());
        assertEquals(2, h.newSideLines().size(), "纯删除 hunk 新侧只剩上下文行");
        assertEquals(2, h.newEndLine());
    }
}
