package com.codereview.git;

import com.codereview.common.BusinessException;
import org.eclipse.jgit.api.Git;

import java.io.File;
import org.eclipse.jgit.lib.PersonIdent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 固定提交净变化：共同祖先到源提交，不用第一父提交，也不把无共同祖先退回某一侧。 */
class FixedComparisonTest {

    private Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Path.of("target", "fixed-comparison-test", UUID.randomUUID().toString());
        Files.createDirectories(tempDir);
    }

    @Test
    void netDiffUsesMergeBaseAndSkipsCommitsThatExistOnlyOnTheTarget() throws Exception {
        Path work = tempDir.resolve("work");
        String base;
        String source;
        String target;
        try (Git git = Git.init().setDirectory(work.toFile()).call()) {
            String main = git.getRepository().getBranch();
            base = commit(git, work, Map.of("a.txt", "1\n"), "base");
            git.checkout().setCreateBranch(true).setName("feature").setStartPoint(base).call();
            source = commit(git, work, Map.of("a.txt", "2\n"), "source");
            git.checkout().setName(main).call();
            target = commit(git, work, Map.of("c.txt", "only-target\n"), "target");
        }

        FixedComparison.Result result = FixedComparison.between(gitDir(work), target, source, 10, 1024);

        assertEquals(base, result.mergeBase());
        assertEquals(1, result.fileCount());
        assertEquals("a.txt", result.files().get(0).path());
        assertEquals(ChangedFile.MODIFIED, result.files().get(0).status());
        assertTrue(result.files().get(0).text());
    }

    @Test
    void repeatedEditsOfOneFileStayOneNetChangeAndARevertDisappears() throws Exception {
        Path work = tempDir.resolve("work");
        String base;
        String edited;
        String reverted;
        try (Git git = Git.init().setDirectory(work.toFile()).call()) {
            base = commit(git, work, Map.of("a.txt", "1\n"), "base");
            edited = commit(git, work, Map.of("a.txt", "2\n"), "edit");
            reverted = commit(git, work, Map.of("a.txt", "1\n"), "revert");
        }

        FixedComparison.Result editedResult = FixedComparison.between(gitDir(work), base, edited, 10, 1024);
        FixedComparison.Result revertedResult = FixedComparison.between(gitDir(work), base, reverted, 10, 1024);

        assertEquals(1, editedResult.files().size());
        assertTrue(revertedResult.files().isEmpty());
    }

    @Test
    void classifiesRenameDeleteBinaryAndUnreadableContent() throws Exception {
        Path work = tempDir.resolve("work");
        String base;
        String source;
        try (Git git = Git.init().setDirectory(work.toFile()).call()) {
            base = commit(git, work, Map.of("keep.txt", "same\n", "gone.txt", "old\n", "link.txt", "text\n"), "base");
            git.rm().addFilepattern("keep.txt").call();
            Files.writeString(work.resolve("moved.txt"), "same\n", StandardCharsets.UTF_8);
            git.add().addFilepattern("moved.txt").call();
            git.rm().addFilepattern("gone.txt").call();
            Files.write(work.resolve("blob.bin"), new byte[] {'a', 0, 'b'});
            git.add().addFilepattern("blob.bin").call();
            Files.write(work.resolve("bad.txt"), new byte[] {(byte) 0xff, (byte) 0xfe});
            git.add().addFilepattern("bad.txt").call();
            Files.writeString(work.resolve("lfs.txt"),
                    "version https://git-lfs.github.com/spec/v1\noid sha256:abc\nsize 1\n",
                    StandardCharsets.UTF_8);
            git.add().addFilepattern("lfs.txt").call();
            source = git.commit().setMessage("source").setSign(false).setAuthor(ident()).setCommitter(ident()).call().name();
        }

        FixedComparison.Result result = FixedComparison.between(gitDir(work), base, source, 10, 1024);
        Map<String, FixedComparison.FileChange> byPath = result.files().stream()
                .collect(java.util.stream.Collectors.toMap(FixedComparison.FileChange::path, file -> file));

        assertEquals(ChangedFile.RENAMED, byPath.get("moved.txt").status());
        assertEquals("keep.txt", byPath.get("moved.txt").oldPath());
        assertEquals(ChangedFile.REMOVED, byPath.get("gone.txt").status());
        assertEquals(FixedComparison.BINARY, byPath.get("blob.bin").category());
        assertEquals("ENCODING", byPath.get("bad.txt").reason());
        assertEquals("LFS_POINTER", byPath.get("lfs.txt").reason());
        assertTrue(byPath.get("moved.txt").text());
    }

    @Test
    void truncatedComparisonDoesNotReturnAPartialPathList() throws Exception {
        Path work = tempDir.resolve("work");
        String base;
        String source;
        try (Git git = Git.init().setDirectory(work.toFile()).call()) {
            base = commit(git, work, Map.of("a.txt", "1\n"), "base");
            source = commit(git, work, Map.of("a.txt", "2\n", "b.txt", "3\n"), "source");
        }

        FixedComparison.Result result = FixedComparison.between(gitDir(work), base, source, 1, 1024);

        assertEquals(FixedComparison.TRUNCATED, result.completeness());
        assertEquals(2, result.fileCount());
        assertTrue(result.files().isEmpty());
    }

    @Test
    void missingMergeBaseFailsInsteadOfUsingEitherSide() throws Exception {
        Path work = tempDir.resolve("work");
        String mainTip;
        String orphanTip;
        try (Git git = Git.init().setDirectory(work.toFile()).call()) {
            mainTip = commit(git, work, Map.of("a.txt", "1\n"), "main");
            git.checkout().setOrphan(true).setName("orphan").call();
            Files.writeString(work.resolve("b.txt"), "2\n", StandardCharsets.UTF_8);
            git.add().addFilepattern("b.txt").call();
            orphanTip = git.commit().setMessage("orphan").setSign(false).setAuthor(ident()).setCommitter(ident()).call().name();
        }

        BusinessException failure = assertThrows(BusinessException.class,
                () -> FixedComparison.between(gitDir(work), mainTip, orphanTip, 10, 1024));

        assertTrue(failure.getMessage().contains("没有共同祖先"));
    }

    private static File gitDir(Path work) {
        return work.resolve(".git").toFile();
    }

    private static PersonIdent ident() {
        return new PersonIdent("tester", "tester@example.com");
    }

    private static String commit(Git git, Path work, Map<String, String> files, String message) throws Exception {
        for (Map.Entry<String, String> file : files.entrySet()) {
            Path path = work.resolve(file.getKey());
            Files.createDirectories(path.getParent());
            Files.writeString(path, file.getValue(), StandardCharsets.UTF_8);
            git.add().addFilepattern(file.getKey()).call();
        }
        return git.commit().setMessage(message).setSign(false).setAuthor(ident()).setCommitter(ident()).call().name();
    }
}
