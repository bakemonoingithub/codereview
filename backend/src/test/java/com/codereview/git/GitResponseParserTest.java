package com.codereview.git;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 解析层单测：覆盖 GitHub / Gitea 的能力差异（patch 可空、重命名可缺、顺序不稳定）。 */
class GitResponseParserTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String s) {
        try {
            return MAPPER.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------- Link 分页头 ----------------

    @Test
    void hasNextPageDetectsRelNext() {
        assertTrue(GitResponseParser.hasNextPage(
                "<https://api.github.com/repos/o/r/commits?page=2>; rel=\"next\", "
                        + "<https://api.github.com/repos/o/r/commits?page=9>; rel=\"last\""));
        assertFalse(GitResponseParser.hasNextPage(
                "<https://api.github.com/repos/o/r/commits?page=1>; rel=\"prev\", "
                        + "<https://api.github.com/repos/o/r/commits?page=9>; rel=\"last\""));
        assertFalse(GitResponseParser.hasNextPage(null));
        assertFalse(GitResponseParser.hasNextPage(""));
    }

    // ---------------- files[] ----------------

    @Test
    void parseFilesReadsGithubShapeAndSortsByPath() {
        // 故意把 b 放在 a 前：Gitea 的 files[] 由 Go map 遍历生成、顺序不稳定，必须稳定排序
        String body = """
                [
                  {"filename":"b/B.java","status":"modified","additions":3,"deletions":1,"changes":4,
                   "patch":"@@ -1 +1 @@\\n-old\\n+new"},
                  {"filename":"a/A.java","status":"added","additions":10,"deletions":0,"changes":10,
                   "patch":"@@ -0,0 +1 @@"}
                ]
                """;
        List<ChangedFile> files = GitResponseParser.parseFiles(json(body));

        assertEquals(2, files.size());
        assertEquals("a/A.java", files.get(0).path());
        assertEquals("b/B.java", files.get(1).path());
        assertTrue(files.get(0).added());
        assertEquals(10, files.get(0).additions().intValue());
        assertTrue(files.get(0).hasPatch());
        assertFalse(files.get(0).removed());
    }

    @Test
    void parseFilesKeepsNullPatchWhenHostOmitsIt() {
        // Gitea 的 commit/compare JSON 从不返回 patch；GitHub 的二进制/过大 diff 也可能缺
        String body = """
                [{"filename":"a/A.java","status":"modified","additions":1,"deletions":1,"changes":2}]
                """;
        List<ChangedFile> files = GitResponseParser.parseFiles(json(body));

        assertEquals(1, files.size());
        assertNull(files.get(0).patch());
        assertFalse(files.get(0).hasPatch());
        assertEquals(2, files.get(0).changes().intValue());
    }

    @Test
    void parseFilesDetectsRenameWithPreviousFilename() {
        String body = """
                [{"filename":"new/Name.java","previous_filename":"old/Name.java",
                  "status":"renamed","changes":0}]
                """;
        ChangedFile f = GitResponseParser.parseFiles(json(body)).get(0);

        assertTrue(f.renamed());
        assertEquals("old/Name.java", f.previousPath());
        assertEquals(ChangedFile.RENAMED, f.status());
    }

    @Test
    void parseFilesInfersRenameWhenStatusMissing() {
        String body = """
                [{"filename":"new/Name.java","previous_filename":"old/Name.java"}]
                """;
        ChangedFile f = GitResponseParser.parseFiles(json(body)).get(0);

        assertEquals(ChangedFile.RENAMED, f.status());
        assertTrue(f.renamed());
    }

    @Test
    void parseFilesNormalizesStatusToLowerCase() {
        String body = """
                [{"filename":"a/A.java","status":"Added"}]
                """;
        assertEquals(ChangedFile.ADDED, GitResponseParser.parseFiles(json(body)).get(0).status());
    }

    @Test
    void parseFilesSkipsEntriesWithoutFilenameAndToleratesMissingNode() {
        assertEquals(0, GitResponseParser.parseFiles(json("[{\"status\":\"modified\"}]")).size());
        assertEquals(0, GitResponseParser.parseFiles(json("{}").path("files")).size());
        assertEquals(0, GitResponseParser.parseFiles(null).size());
    }

    // ---------------- 单提交详情 ----------------

    @Test
    void parseCommitReadsParentsStatsTruncationAndFiles() {
        String body = """
                {
                  "sha":"abc1234",
                  "parents":[{"sha":"p1"},{"sha":"p2"}],
                  "commit":{"message":"merge feature","author":{"name":"张三","date":"2026-09-03T21:00:00Z"}},
                  "stats":{"additions":30,"deletions":10,"total":40},
                  "files":[{"filename":"a/A.java","status":"modified","patch":"@@ -1 +1 @@"}]
                }
                """;
        CommitDetail d = GitResponseParser.parseCommit(json(body), true);

        assertEquals("abc1234", d.sha());
        assertEquals(List.of("p1", "p2"), d.parents());
        assertTrue(d.merge(), "多父应判定为 merge 提交");
        assertEquals("p1", d.baseSha(), "base 取第一父提交");
        assertEquals("merge feature", d.message());
        assertEquals("张三", d.author());
        assertEquals(30, d.additions().intValue());
        assertEquals(40, d.totalChanges().intValue());
        assertTrue(d.truncated());
        assertEquals(1, d.fileCount());
    }

    @Test
    void parseCommitWithoutParentsOrFilesIsTolerated() {
        String body = """
                {"sha":"root1","parents":[],"commit":{"message":"init"}}
                """;
        CommitDetail d = GitResponseParser.parseCommit(json(body), false);

        assertFalse(d.merge());
        assertNull(d.baseSha());
        assertEquals(0, d.fileCount());
        assertNull(d.additions());
        assertFalse(d.truncated());
    }

    // ---------------- 提交列表 ----------------

    @Test
    void parseCommitInfoReadsListShape() {
        String body = """
                {"sha":"s1","commit":{"message":"fix bug","author":{"name":"李四","date":"2026-09-01T10:00:00Z"}}}
                """;
        CommitInfo c = GitResponseParser.parseCommitInfo(json(body));

        assertEquals("s1", c.sha());
        assertEquals("fix bug", c.message());
        assertEquals("李四", c.author());
        assertEquals("2026-09-01T10:00:00Z", c.date());
    }

    @Test
    void parseCommitInfoToleratesMissingAuthor() {
        CommitInfo c = GitResponseParser.parseCommitInfo(json("{\"sha\":\"s2\"}"));

        assertEquals("s2", c.sha());
        assertNull(c.message());
        assertNull(c.author());
    }
}
