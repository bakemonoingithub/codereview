package com.codereview.agent.tools;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 真实 git 进程上的受限检索：字面量、原始行号、退出码与路径限定。 */
class GitGrepRunnerTest {

    @TempDir
    Path temp;

    private Path repo;
    private String sha;
    private final ToolProperties properties = new ToolProperties();

    @BeforeEach
    void init() throws Exception {
        Assumptions.assumeTrue(gitAvailable(), "本机没有 git，跳过真实 grep 回归");
        repo = temp.resolve("repo");
        Files.createDirectories(repo.resolve("src/nested"));
        Files.createDirectories(repo.resolve("docs"));
        Files.writeString(repo.resolve("src/A.java"),
                "class A {\n  String s = \"needle\";\n}\n", StandardCharsets.UTF_8);
        Files.writeString(repo.resolve("src/nested/B.java"),
                "class B {\n  // needle here\n}\n", StandardCharsets.UTF_8);
        Files.writeString(repo.resolve("docs/readme.md"), "needle in docs\n", StandardCharsets.UTF_8);
        git("init", "-q");
        git("config", "user.email", "test@example.com");
        git("config", "user.name", "test");
        git("add", "-A");
        git("commit", "-qm", "init");
        sha = git("rev-parse", "HEAD").trim();
    }

    @Test
    void returnsLiteralHitsWithOriginalLineNumbers() {
        GitGrepRunner.Result result = runner().search(repo.toFile(), sha, "needle", null,
                Duration.ofSeconds(10));

        assertEquals(0, result.exitCode());
        assertFalse(result.timedOut());
        assertNull(result.diagnostic());
        assertEquals(3, result.hits().size());
        assertTrue(result.hits().stream().anyMatch(hit ->
                hit.path().equals("src/A.java") && hit.lineNumber() == 2
                        && hit.text().contains("needle")));
        assertTrue(result.hits().stream().anyMatch(hit ->
                hit.path().equals("src/nested/B.java") && hit.lineNumber() == 2));
        assertTrue(result.hits().stream().anyMatch(hit -> hit.path().equals("docs/readme.md")));
    }

    @Test
    void pathPrefixRestrictsTheSearch() {
        GitGrepRunner.Result result = runner().search(repo.toFile(), sha, "needle", "src/A.java",
                Duration.ofSeconds(10));

        assertEquals(1, result.hits().size());
        assertEquals("src/A.java", result.hits().get(0).path());
    }

    @Test
    void noMatchIsExitOneWithoutDiagnostic() {
        GitGrepRunner.Result result = runner().search(repo.toFile(), sha, "no-such-token", null,
                Duration.ofSeconds(10));

        assertEquals(1, result.exitCode());
        assertTrue(result.hits().isEmpty());
        assertNull(result.diagnostic());
        assertFalse(result.timedOut());
    }

    @Test
    void regexMetacharactersAreMatchedLiterally() {
        GitGrepRunner.Result result = runner().search(repo.toFile(), sha, "String s = \"needle\"",
                null, Duration.ofSeconds(10));

        assertEquals(1, result.hits().size(), "exit=" + result.exitCode()
                + " diagnostic=" + result.diagnostic() + " hits=" + result.hits());
        assertEquals(2, result.hits().get(0).lineNumber());
    }

    @Test
    void missingGitExecutableIsReportedInsteadOfFakingAnEmptyResult() {
        properties.setGitExecutable("definitely-not-a-git-binary");

        GitGrepRunner.Result result = runner().search(repo.toFile(), sha, "needle", null,
                Duration.ofSeconds(10));

        assertNotNull(result.diagnostic());
        assertTrue(result.diagnostic().contains("GIT_UNAVAILABLE"), result.diagnostic());
        assertTrue(result.hits().isEmpty());
    }

    private GitGrepRunner runner() {
        return new GitGrepRunner(properties);
    }

    private boolean gitAvailable() {
        try {
            Process process = new ProcessBuilder("git", "--version").start();
            return process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private String git(String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(repo.toFile());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("git 超时：" + command);
        }
        if (process.exitValue() != 0 && !args[0].equals("grep")) {
            throw new IllegalStateException("git 失败：" + command + "\n" + output);
        }
        return output;
    }
}
