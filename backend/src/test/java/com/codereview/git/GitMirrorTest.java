package com.codereview.git;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 共享镜像回归：JGit 克隆出来的 bare mirror 必须能被 git CLI 读取固定提交。 */
class GitMirrorTest {

    @TempDir
    Path temp;

    @Test
    void cloneFromCliRepositoryExposesTheCommit() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(gitAvailable(), "本机没有 git，跳过镜像回归");
        Path source = java.nio.file.Files.createDirectories(Path.of("target", "p33-mirror-probe",
                java.util.UUID.randomUUID().toString(), "source"));
        Files.createDirectories(source.resolve("src"));
        Files.writeString(source.resolve("src/A.java"), "class A { void a() { /* needle */ } }\n",
                StandardCharsets.UTF_8);
        Files.writeString(source.resolve("notes.md"), "needle docs\n", StandardCharsets.UTF_8);
        git(source, "init", "-q");
        git(source, "config", "user.email", "t@t");
        git(source, "config", "user.name", "t");
        git(source, "add", "-A");
        git(source, "commit", "-qm", "init");
        String sha = git(source, "rev-parse", "HEAD").trim();

        GitMirror mirror = new GitMirror(temp.resolve("mirrors").toFile(), 120);
        File dir = mirror.using(source.toUri().toString(), null, 1, List.of(sha), null, d -> d);
        File again = mirror.using(source.toUri().toString(), null, 1, List.of(sha),
                java.time.Duration.ofSeconds(5), d -> d);

        assertTrue(mirror.hasObject(again, sha));
        Process process = new ProcessBuilder("git", "-C", dir.getAbsolutePath(), "cat-file", "-t", sha)
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(10, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), output);
        assertTrue(output.trim().equals("commit"), output);
    }

    private static boolean gitAvailable() {
        try {
            Process process = new ProcessBuilder("git", "--version").start();
            return process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (java.io.IOException | InterruptedException e) {
            return false;
        }
    }

    private static String git(Path dir, String... args) throws Exception {
        List<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).directory(dir.toFile());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new IllegalStateException("git 失败：" + command + "\n" + output);
        }
        return output;
    }
}
