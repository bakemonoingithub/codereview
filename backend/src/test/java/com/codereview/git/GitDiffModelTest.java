package com.codereview.git;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 变更模型单测：patch 剥离（清单与 patch 分离）、状态判定、基线推导。 */
class GitDiffModelTest {

    private static ChangedFile file(String path, String status, String patch) {
        return new ChangedFile(path, null, status, 1, 1, 2, patch);
    }

    // ---------------- ChangedFile ----------------

    @Test
    void withoutPatchStripsPatchButKeepsMetadata() {
        ChangedFile withPatch = file("a/A.java", ChangedFile.MODIFIED, "@@ -1 +1 @@");
        ChangedFile stripped = withPatch.withoutPatch();

        assertNull(stripped.patch());
        assertFalse(stripped.hasPatch());
        assertEquals("a/A.java", stripped.path());
        assertEquals(ChangedFile.MODIFIED, stripped.status());
        assertEquals(1, stripped.additions().intValue());
    }

    @Test
    void withoutPatchOnAlreadyStrippedFileReturnsSameInstance() {
        ChangedFile noPatch = file("a/A.java", ChangedFile.MODIFIED, null);
        assertSame(noPatch, noPatch.withoutPatch());
    }

    @Test
    void statusClassification() {
        assertTrue(file("a", ChangedFile.ADDED, null).added());
        assertTrue(file("a", ChangedFile.REMOVED, null).removed());
        assertFalse(file("a", ChangedFile.ADDED, null).removed());
        // 重命名：状态为 renamed 或带原路径
        assertTrue(file("a", ChangedFile.RENAMED, null).renamed());
        assertTrue(new ChangedFile("n", "o", ChangedFile.ADDED, null, null, null, null).renamed());
        assertFalse(file("a", ChangedFile.MODIFIED, null).renamed());
    }

    @Test
    void blankPatchCountsAsNoPatch() {
        assertFalse(file("a", ChangedFile.MODIFIED, "   ").hasPatch());
    }

    // ---------------- CommitDetail ----------------

    @Test
    void withoutPatchesStripsEveryFile() {
        CommitDetail detail = new CommitDetail("sha", List.of("p1", "p2"), "msg", "author", "date",
                10, 5, 15, true,
                List.of(file("a/A.java", ChangedFile.MODIFIED, "@@ -1 +1 @@"),
                        file("b/B.java", ChangedFile.ADDED, "@@ -0,0 +1 @@")));

        CommitDetail stripped = detail.withoutPatches();

        assertEquals(2, stripped.fileCount());
        assertTrue(stripped.files().stream().noneMatch(ChangedFile::hasPatch));
        // 其余字段原样保留
        assertEquals("sha", stripped.sha());
        assertEquals(2, stripped.parents().size());
        assertEquals(15, stripped.totalChanges().intValue());
        assertTrue(stripped.truncated());
    }

    @Test
    void withoutPatchesOnEmptyFilesYieldsEmptyList() {
        CommitDetail detail = new CommitDetail("sha", List.of(), "msg", "a", "d", null, null, null, false, null);
        assertEquals(0, detail.withoutPatches().fileCount());
        assertEquals(0, detail.fileCount());
    }

    @Test
    void baseShaAndMergeDerivation() {
        CommitDetail single = new CommitDetail("s", List.of("p1"), "m", "a", "d", null, null, null, false, List.of());
        assertFalse(single.merge());
        assertEquals("p1", single.baseSha());

        CommitDetail merge = new CommitDetail("s", List.of("p1", "p2"), "m", "a", "d", null, null, null, false, List.of());
        assertTrue(merge.merge());
        assertEquals("p1", merge.baseSha(), "merge 提交与第一父提交比较");

        CommitDetail root = new CommitDetail("s", List.of(), "m", "a", "d", null, null, null, false, List.of());
        assertNull(root.baseSha(), "首个提交无父，base 为 null");
    }
}
