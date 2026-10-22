package com.codereview.agent.tools;

import com.codereview.agent.api.ApiException;
import com.codereview.agent.api.ErrorCode;
import com.codereview.agent.repository.RepositoryReadService;
import com.codereview.agent.repository.RepositorySnapshots;
import com.codereview.git.GiteaProperties;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 共享镜像上的真实检索：克隆固定版本、命中上下文、后缀过滤与分页。 */
class SearchCodeToolTest {

    /** 放在 target 下，避免 Windows 上 JGit 包文件被锁导致 JUnit 临时目录清理失败。 */
    private Path temp;

    private Path repo;
    private String sha;
    private RepositoryReadService repository;
    private SearchCodeTool tool;

    @BeforeEach
    void init() throws Exception {
        Assumptions.assumeTrue(gitAvailable(), "本机没有 git，跳过真实镜像检索回归");
        // 使用短临时路径：Windows 默认 MAX_PATH 下，深层 target 目录加镜像名会让 git 读不到 pack 文件。
        temp = Files.createTempDirectory("p33-search-");
        repo = temp.resolve("source");
        Files.createDirectories(repo.resolve("src"));
        Files.writeString(repo.resolve("src/A.java"),
                "class A {\n  void a() {\n    // needle first\n  }\n}\n", StandardCharsets.UTF_8);
        Files.writeString(repo.resolve("src/B.java"),
                "class B {\n  void b() {\n    // needle second\n  }\n}\n", StandardCharsets.UTF_8);
        Files.writeString(repo.resolve("notes.md"), "needle docs\n", StandardCharsets.UTF_8);
        git("init", "-q");
        git("config", "user.email", "test@example.com");
        git("config", "user.name", "test");
        git("add", "-A");
        git("commit", "-qm", "init");
        sha = git("rev-parse", "HEAD").trim();

        repository = mock(RepositoryReadService.class);
        when(repository.mirrorTarget("p1")).thenReturn(
                new RepositoryReadService.MirrorTarget("p1", repo.toUri().toString(), null, 1, "p1|r1"));
        when(repository.readFile(eq("p1"), eq(sha), anyString())).thenAnswer(invocation -> {
            String path = invocation.getArgument(2);
            Path file = repo.resolve(path);
            String content = Files.readString(file, StandardCharsets.UTF_8);
            return new RepositorySnapshots.FileSnapshot(sha, path, content, ToolTexts.sha256(content),
                    content.getBytes(StandardCharsets.UTF_8).length,
                    ToolTexts.splitLines(content).length);
        });

        ToolMaterialStore store = mock(ToolMaterialStore.class);
        when(store.save(anyString(), any())).thenAnswer(invocation -> {
            ToolEnvelope.ToolMaterial draft = invocation.getArgument(1);
            return new ToolEnvelope.ToolMaterial("m-" + draft.path() + "-" + draft.startLine(),
                    draft.path(), draft.side(), draft.sha(), draft.startLine(), draft.endLine(),
                    draft.contentHash(), draft.completeness(), draft.content());
        });
        GiteaProperties gitea = new GiteaProperties();
        gitea.setWorkDir(temp.resolve("mirrors").toString());
        tool = new SearchCodeTool(repository, store, gitea, new GitGrepRunner(new ToolProperties()),
                new ToolProperties());
    }

    @Test
    void findsLiteralHitsAndBuildsContextMaterials() {
        ToolEnvelope envelope = tool.execute("call-1", invocation(null, List.of(), 50), scope());

        assertEquals(ToolEnvelope.OK, envelope.status(), envelope.diagnostic());
        assertEquals(ToolEnvelope.COMPLETE, envelope.completeness(), envelope.diagnostic());
        assertEquals(3, envelope.materials().size());
        List<Map<String, Object>> hits = hits(envelope);
        assertEquals(3, hits.size());
        int index = indexOf(hits, "src/A.java");
        assertEquals(3, hits.get(index).get("line"));
        ToolEnvelope.ToolMaterial material = envelope.materials().get(index);
        assertEquals(1, material.startLine());
        assertEquals(5, material.endLine());
        assertTrue(material.content().contains("needle"));
        assertNotNull(hits.get(index).get("materialId"));
    }

    @Test
    void extensionFilterAppliesToHits() {
        ToolEnvelope envelope = tool.execute("call-1", invocation(null, List.of(".md"), 50), scope());

        assertEquals(ToolEnvelope.COMPLETE, envelope.completeness());
        assertEquals(1, hits(envelope).size());
        assertEquals("notes.md", hits(envelope).get(0).get("path"));
    }

    @Test
    void limitsReturnAPageAndANextCursor() {
        ToolEnvelope first = tool.execute("call-1", invocation(null, List.of(), 2), scope());

        assertEquals(ToolEnvelope.PARTIAL, first.completeness());
        assertEquals(2, hits(first).size());
        assertNotNull(first.nextCursor());

        ToolEnvelope second = tool.execute("call-2", new ToolInvocation(AgentToolNames.SEARCH_CODE,
                "V1", sha, null, null, null, 2, first.nextCursor(), null, "needle", List.of(),
                null, null), scope());
        assertEquals(ToolEnvelope.COMPLETE, second.completeness());
        assertEquals(1, hits(second).size());
        assertNull(second.nextCursor());
        assertTrue(!hits(second).get(0).get("path").equals(hits(first).get(0).get("path")));
    }

    @Test
    void zeroHitsIsCompleteAndExplicit() {
        ToolEnvelope envelope = execute("call-1", invocation(null, List.of(), 50), scope(),
                "no-such-token");

        assertEquals(ToolEnvelope.OK, envelope.status());
        assertEquals(ToolEnvelope.COMPLETE, envelope.completeness());
        assertTrue(hits(envelope).isEmpty());
        assertTrue(envelope.materials().isEmpty());
    }

    @Test
    void oversizedContextKeepsTheLineRangeInsideTheStoredBytes() throws Exception {
        String huge = "x".repeat(30_000) + " oversized-needle " + "y".repeat(1_000);
        Files.writeString(repo.resolve("src/Huge.java"), "head\n" + huge + "\ntail\n", StandardCharsets.UTF_8);
        git("add", "-A");
        git("commit", "-qm", "huge");
        sha = git("rev-parse", "HEAD").trim();
        when(repository.readFile(eq("p1"), eq(sha), anyString())).thenAnswer(invocation -> {
            String path = invocation.getArgument(2);
            String content = Files.readString(repo.resolve(path), StandardCharsets.UTF_8);
            return new RepositorySnapshots.FileSnapshot(sha, path, content, ToolTexts.sha256(content),
                    content.getBytes(StandardCharsets.UTF_8).length, ToolTexts.splitLines(content).length);
        });
        GiteaProperties gitea = new GiteaProperties();
        gitea.setWorkDir(temp.resolve("mirrors-huge").toString());
        ToolMaterialStore store = mock(ToolMaterialStore.class);
        when(store.save(anyString(), any())).thenAnswer(invocation -> {
            ToolEnvelope.ToolMaterial draft = invocation.getArgument(1);
            return new ToolEnvelope.ToolMaterial("m-huge", draft.path(), draft.side(), draft.sha(),
                    draft.startLine(), draft.endLine(), draft.contentHash(), draft.completeness(),
                    draft.content());
        });
        SearchCodeTool hugeTool = new SearchCodeTool(repository, store, gitea,
                new GitGrepRunner(new ToolProperties()), new ToolProperties());

        ToolEnvelope envelope = hugeTool.execute("call-huge", new ToolInvocation(
                AgentToolNames.SEARCH_CODE, "V1", sha, null, "src/Huge.java", null, 10, null, null,
                "oversized-needle", List.of(), null, null), scope());

        assertEquals(ToolEnvelope.OK, envelope.status(), envelope.diagnostic());
        assertEquals(1, envelope.materials().size());
        ToolEnvelope.ToolMaterial material = envelope.materials().get(0);
        assertEquals(2, material.startLine());
        assertEquals(2, material.endLine());
        assertEquals(ToolEnvelope.PARTIAL, material.completeness());
        assertTrue(ToolTexts.utf8Bytes(material.content()) <= ToolLimits.MATERIAL_MAX_BYTES);
        assertTrue(material.content().contains("已截短"));
        assertEquals(1, ToolTexts.splitLines(material.content()).length);
    }

    @Test
    void unreadableContextKeepsTheHitLineAndSaysSo() {
        when(repository.readFile(eq("p1"), eq(sha), anyString())).thenThrow(
                new ApiException(ErrorCode.FILE_NOT_READABLE, "二进制文件"));

        ToolEnvelope envelope = tool.execute("call-1", invocation(null, List.of(), 50), scope());

        assertEquals(ToolEnvelope.OK, envelope.status());
        assertEquals(3, envelope.materials().size());
        assertTrue(envelope.materials().stream()
                .allMatch(material -> ToolEnvelope.UNKNOWN.equals(material.completeness())));
        assertTrue(envelope.diagnostic().contains("无法读取上下文"));
    }

    private ToolInvocation invocation(String path, List<String> extensions, int limit) {
        return new ToolInvocation(AgentToolNames.SEARCH_CODE, "V1", sha, null, path, null, limit,
                null, null, "needle", extensions, null, null);
    }

    private ToolEnvelope execute(String toolCallId, ToolInvocation invocation, AgentToolScope scope,
                                 String query) {
        return tool.execute(toolCallId, new ToolInvocation(invocation.tool(), invocation.versionRef(),
                invocation.sha(), invocation.comparisonId(), invocation.path(), invocation.depth(),
                invocation.limit(), invocation.cursor(), invocation.glob(), query,
                invocation.extensions(), invocation.start(), invocation.end()), scope);
    }

    private AgentToolScope scope() {
        return AgentToolScope.single("p1", sha);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> hits(ToolEnvelope envelope) {
        return (List<Map<String, Object>>) ((Map<?, ?>) envelope.data()).get("hits");
    }

    private static int indexOf(List<Map<String, Object>> hits, String path) {
        for (int index = 0; index < hits.size(); index++) {
            if (path.equals(hits.get(index).get("path"))) {
                return index;
            }
        }
        throw new AssertionError("没有命中：" + path);
    }

    private static boolean gitAvailable() {
        try {
            Process process = new ProcessBuilder("git", "--version").start();
            return process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private String git(String... args) throws Exception {
        List<String> command = new java.util.ArrayList<>();
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
        if (process.exitValue() != 0) {
            throw new IllegalStateException("git 失败：" + command + "\n" + output);
        }
        return output;
    }
}
