package com.codereview.git;

import com.codereview.common.BusinessException;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gitea 的 compare（{@code base...head}）用本地 bare 镜像自算 —— 这里用**真的 git 仓库**
 * （JGit 现建，file:// 克隆）验证语义，而不是打桩：
 *
 * <pre>
 *        c1 ── feature: c2 (改 a.txt)
 *         └── main:    c3 (加 c.txt)
 * </pre>
 *
 * 三点 diff {@code c3...c2} 的 merge-base 是 {@code c1}，应只报 {@code a.txt}；
 * 若误用两点 {@code c3..c2}，会多报 {@code c.txt}（那是 main 独有的变更）——这正是
 * "与 GitHub compare 语义一致"的关键差别。
 *
 * <p>目录建在 {@code target/} 下而不是 {@code @TempDir}：JGit 的 WindowCache 会持有 pack
 * 文件句柄（Windows 上删不掉），用 @TempDir 会把"清理失败"当成测试失败，反而掩盖真问题。
 */
class GiteaGitMirrorTest {

    private Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Path.of("target", "gitea-mirror-test", UUID.randomUUID().toString());
        Files.createDirectories(tempDir);
    }

    @Test
    void usesMergeBaseThreeDotSemanticsInsteadOfTwoDot() throws Exception {
        Path work = tempDir.resolve("work");
        String mainBranch;
        String c1;
        String c2;
        String c3;
        try (Git git = Git.init().setDirectory(work.toFile()).call()) {
            mainBranch = git.getRepository().getBranch();
            c1 = commit(git, work, Map.of("a.txt", "1\n"), "c1");
            git.checkout().setCreateBranch(true).setName("feature").setStartPoint(c1).call();
            c2 = commit(git, work, Map.of("a.txt", "2\n"), "c2");
            git.checkout().setName(mainBranch).call();
            c3 = commit(git, work, Map.of("c.txt", "3\n"), "c3");
        }

        List<String> files = mirror().changedPaths(work.toUri().toString(), null, null, c3, c2);

        assertEquals(List.of("a.txt"), files,
                "三点语义只报 head 相对 merge-base 的变更；多报 c.txt 说明退化成了两点 diff");
    }

    @Test
    void reportsAddedFilesAndReusesTheSameMirrorOnSecondCall() throws Exception {
        Path work = tempDir.resolve("work");
        String c1;
        String c2;
        try (Git git = Git.init().setDirectory(work.toFile()).call()) {
            String branch = git.getRepository().getBranch();
            c1 = commit(git, work, Map.of("a.txt", "1\n"), "c1");
            Files.writeString(work.resolve("b.txt"), "new\n", StandardCharsets.UTF_8);
            git.add().addFilepattern(".").call();
            RevCommit commit = git.commit().setMessage("c2").setAuthor(ident()).setCommitter(ident()).call();
            c2 = commit.name();
            assertEquals(branch, git.getRepository().getBranch());
        }

        GiteaGitMirror mirror = mirror();
        String url = work.toUri().toString();

        assertEquals(List.of("b.txt"), mirror.changedPaths(url, null, null, c1, c2));
        // 第二次走已存在的镜像（fetch 而非重新 clone），结果必须一致
        assertEquals(List.of("b.txt"), mirror.changedPaths(url, null, null, c1, c2));
    }

    @Test
    void unknownRefFailsLoudlyInsteadOfReturningEmptyList() throws Exception {
        Path work = tempDir.resolve("work");
        String c1;
        try (Git git = Git.init().setDirectory(work.toFile()).call()) {
            c1 = commit(git, work, Map.of("a.txt", "1\n"), "c1");
        }

        BusinessException e = assertThrows(BusinessException.class,
                () -> mirror().changedPaths(work.toUri().toString(), null, null,
                        "deadbeefdeadbeefdeadbeefdeadbeefdeadbeef", c1));

        assertEquals(5005, e.getCode());
        assertTrue(e.getMessage().contains("找不到引用"), e.getMessage());
    }

    private GiteaGitMirror mirror() {
        GiteaProperties props = new GiteaProperties();
        props.setWorkDir(tempDir.resolve("mirrors").toString());
        return new GiteaGitMirror(props);
    }

    private static PersonIdent ident() {
        return new PersonIdent("tester", "tester@example.com");
    }

    private static String commit(Git git, Path work, Map<String, String> files, String message)
            throws IOException, Exception {
        for (Map.Entry<String, String> e : files.entrySet()) {
            Path file = work.resolve(e.getKey());
            Files.createDirectories(file.getParent() == null ? work : file.getParent());
            Files.writeString(file, e.getValue(), StandardCharsets.UTF_8);
        }
        git.add().addFilepattern(".").call();
        return git.commit().setMessage(message).setAuthor(ident()).setCommitter(ident()).call().name();
    }
}
