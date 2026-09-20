package com.codereview.git;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gitea 的 {@code .diff} 文本切分（Gitea 唯一能拿到逐文件 patch 的来源）。
 *
 * <p>口径与 GitHub 的 {@code files[].patch} 对齐：patch 从第一个 {@code @@} 开始，
 * 二进制为 null，状态与重命名从 git 头推导，统计自己数。
 */
class GiteaDiffParserTest {

    private static final String DIFF = """
            diff --git a/src/A.java b/src/A.java
            index 8b0d7d5..9cfd72b 100644
            --- a/src/A.java
            +++ b/src/A.java
            @@ -1,3 +1,4 @@
             class A {
            -    int a = 1;
            +    int a = 2;
            +    int b = 3;
             }
            diff --git a/src/B.java b/src/B.java
            new file mode 100644
            index 0000000..274b312
            --- /dev/null
            +++ b/src/B.java
            @@ -0,0 +1,2 @@
            +class B {
            +}
            diff --git a/docs/old.md b/docs/new.md
            similarity index 100%
            rename from docs/old.md
            rename to docs/new.md
            diff --git a/assets/logo.png b/assets/logo.png
            index aaaa..bbbb 100644
            Binary files a/assets/logo.png and b/assets/logo.png differ
            diff --git a/src/C.java b/src/C.java
            deleted file mode 100644
            index 1234567..0000000
            --- a/src/C.java
            +++ /dev/null
            @@ -1,2 +0,0 @@
            -class C {
            -}
            """;

    @Test
    void parsesEveryFileAndSortsByPath() {
        List<ChangedFile> files = GiteaDiffParser.parse(DIFF);

        assertEquals(List.of("assets/logo.png", "docs/new.md", "src/A.java", "src/B.java", "src/C.java"),
                files.stream().map(ChangedFile::path).toList(),
                "宿主给的文件顺序不稳定，解析后必须稳定排序");
    }

    @Test
    void patchStartsAtFirstHunkAndStatsAreCounted() {
        ChangedFile a = byPath("src/A.java");

        assertEquals(ChangedFile.MODIFIED, a.status());
        assertTrue(a.patch().startsWith("@@ -1,3 +1,4 @@"), "patch 不能带 diff --git/index/---/+++ 头：" + a.patch());
        assertTrue(a.patch().contains("+    int b = 3;"));
        assertEquals(2, a.additions());
        assertEquals(1, a.deletions());
        assertEquals(3, a.changes());
        assertNull(a.previousPath());
    }

    @Test
    void addedFileUsesNewPathAndCountsOnlyAdditions() {
        ChangedFile b = byPath("src/B.java");

        assertEquals(ChangedFile.ADDED, b.status());
        assertEquals(2, b.additions());
        assertEquals(0, b.deletions());
        assertTrue(b.added());
    }

    @Test
    void removedFileKeepsOldPathAndCountsOnlyDeletions() {
        ChangedFile c = byPath("src/C.java");

        assertEquals(ChangedFile.REMOVED, c.status());
        assertEquals(0, c.additions());
        assertEquals(2, c.deletions());
        assertTrue(c.removed());
    }

    @Test
    void renameCarriesPreviousPathAndHasNoPatch() {
        ChangedFile renamed = byPath("docs/new.md");

        assertEquals(ChangedFile.RENAMED, renamed.status());
        assertEquals("docs/old.md", renamed.previousPath());
        assertTrue(renamed.renamed());
        assertNull(renamed.patch(), "纯重命名没有 hunk，patch 为空");
        assertNull(renamed.additions(), "没有 hunk 就没有可数的行，不能伪造成 0");
    }

    @Test
    void binaryFileHasNoPatchAndNoStats() {
        ChangedFile png = byPath("assets/logo.png");

        assertNull(png.patch(), "二进制没有 hunk，patch 置空（前端据此显示为不可审查）");
        assertNull(png.additions());
        assertNull(png.deletions());
        assertEquals(ChangedFile.MODIFIED, png.status());
    }

    @Test
    void blankDiffYieldsEmptyList() {
        assertTrue(GiteaDiffParser.parse("").isEmpty());
        assertTrue(GiteaDiffParser.parse(null).isEmpty());
    }

    private static ChangedFile byPath(String path) {
        return GiteaDiffParser.parse(DIFF).stream()
                .filter(f -> path.equals(f.path()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("没有解析出 " + path));
    }
}
