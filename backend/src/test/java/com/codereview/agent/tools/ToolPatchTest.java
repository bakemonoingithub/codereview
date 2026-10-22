package com.codereview.agent.tools;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolPatchTest {

    @Test
    void parsesStandardHunkHeadersWithRealLineRanges() {
        String patch = """
                diff --git a/src/A.java b/src/A.java
                index 111..222 100644
                --- a/src/A.java
                +++ b/src/A.java
                @@ -10,7 +10,8 @@ class A {
                 context
                -old
                +new
                +extra
                 context
                @@ -40 +41,2 @@ class A {
                -gone
                +added
                """;

        List<ToolPatch.Hunk> hunks = ToolPatch.parse(patch);

        assertEquals(2, hunks.size());
        ToolPatch.Hunk first = hunks.get(0);
        assertEquals(10, first.beforeStart());
        assertEquals(7, first.beforeCount());
        assertEquals(16, first.beforeEnd());
        assertEquals(10, first.afterStart());
        assertEquals(8, first.afterCount());
        assertEquals(17, first.afterEnd());
        assertEquals("class A {", first.header());

        ToolPatch.Hunk second = hunks.get(1);
        assertEquals(40, second.beforeStart());
        assertEquals(1, second.beforeCount());
        assertEquals(41, second.afterStart());
        assertEquals(2, second.afterCount());
    }

    @Test
    void missingCountsDefaultToOneLikeGit() {
        List<ToolPatch.Hunk> hunks = ToolPatch.parse("@@ -5 +6 @@\n-x\n+y\n");

        assertEquals(1, hunks.size());
        assertEquals(1, hunks.get(0).beforeCount());
        assertEquals(1, hunks.get(0).afterCount());
    }

    @Test
    void addedAndRemovedFilesKeepZeroCounts() {
        List<ToolPatch.Hunk> hunks = ToolPatch.parse("@@ -0,0 +1,3 @@\n+a\n+b\n+c\n");

        assertEquals(1, hunks.size());
        assertFalse(hunks.get(0).hasBefore());
        assertTrue(hunks.get(0).hasAfter());
        assertEquals(0, hunks.get(0).beforeCount());
    }

    @Test
    void nonHunkLinesAndEmptyPatchesAreIgnored() {
        assertTrue(ToolPatch.parse(null).isEmpty());
        assertTrue(ToolPatch.parse("").isEmpty());
        assertTrue(ToolPatch.parse("+++ b/A.java\n+not a header\n").isEmpty());
    }
}
