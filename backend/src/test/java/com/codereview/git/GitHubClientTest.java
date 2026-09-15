package com.codereview.git;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * git 客户端与宿主之间的**HTTP 契约测试**。
 *
 * <p>背景：`GitHubClient` 在此之前**从未被任何测试实例化**（全仓也没有 WireMock/MockServer），
 * "URL 拼对了吗、认证头对不对、宿主给的截断信号读了没有"全靠人工核对源码。这里用
 * {@link GitStubServer} 把"实际发出的请求"变成断言。
 *
 * <p>前置改动：`api-base` / `raw-base` 由 `private static final` 改为构造注入 + `git.*` 配置项，
 * 否则客户端无法指向本地桩 —— 典型的"可测"倒逼"可配"。
 *
 * <p>范围：这里**只锁当前行为，不为已知缺陷背书**；已知待修项（如 branches 缺翻页、
 * tree 忽略 `truncated`）在 `dev/待办backlog.md` 登记，不在此处用断言固定下来。
 */
class GitHubClientTest {

    private GitStubServer stub;

    @BeforeEach
    void setUp() throws IOException {
        stub = new GitStubServer();
        stub.start();
    }

    @AfterEach
    void tearDown() {
        stub.stop();
    }

    @Test
    void treeUsesRecursiveQueryAndKeepsOnlyBlobAndTree() {
        stub.respondWith("""
                {"sha":"t1","truncated":false,"tree":[
                  {"path":"src","type":"tree","sha":"a"},
                  {"path":"src/A.java","type":"blob","sha":"b"},
                  {"path":"note.md","type":"commit","sha":"c"}
                ]}
                """);

        List<GitTreeEntry> entries = stub.newClient().tree("", 1, "o", "r", "main");

        assertEquals("/repos/o/r/git/trees/main?recursive=1", stub.uri(0));
        assertEquals(List.of(new GitTreeEntry("src", "tree"), new GitTreeEntry("src/A.java", "blob")), entries);
    }

    @Test
    void branchesHitsThePlainBranchesEndpoint() {
        stub.respondWith("""
                [{"name":"main","commit":{"id":"x"}},{"name":"dev","commit":{"id":"y"}}]
                """);

        List<String> names = stub.newClient().branches("", 1, "o", "r");

        assertEquals("/repos/o/r/branches", stub.uri(0));
        assertEquals(List.of("main", "dev"), names);
    }

    @Test
    void headCommitShaReadsCommitSha() {
        stub.respondWith("{\"name\":\"main\",\"commit\":{\"sha\":\"deadbeef\"}}");

        String sha = stub.newClient().headCommitSha("", 1, "o", "r", "main");

        assertEquals("/repos/o/r/branches/main", stub.uri(0));
        assertEquals("deadbeef", sha);
    }

    @Test
    void commitPageSendsPageAndClampsPerPageToHundred() {
        stub.respondWith("[]");

        stub.newClient().commitPage("", 1, "o", "r", "main", 2, 500);

        assertEquals("/repos/o/r/commits?sha=main&page=2&per_page=100", stub.uri(0));
    }

    @Test
    void commitDetailReadsTruncationFromLinkHeader() {
        stub.respondByPath(exchange -> {
            exchange.getResponseHeaders().add("Link",
                    "<" + stub.base() + "/repos/o/r/commits/sha1?page=2>; rel=\"next\"");
            return "{\"sha\":\"sha1\",\"commit\":{\"message\":\"m\"},\"files\":[{\"filename\":\"a.txt\"}]}";
        });

        CommitDetail detail = stub.newClient().commitDetail("", 1, "o", "r", "sha1");

        assertEquals("/repos/o/r/commits/sha1", stub.uri(0));
        assertTrue(detail.truncated(), "宿主给了 rel=\"next\" 就必须标记不完整，不能当成全量");
        assertEquals(List.of("a.txt"), detail.files().stream().map(ChangedFile::path).toList());
    }

    @Test
    void changedFilesReadsCompareFilenames() {
        stub.respondWith("{\"files\":[{\"filename\":\"a.txt\"},{\"filename\":\"b/c.txt\"},{\"filename\":\"\"}]}");

        List<String> files = stub.newClient().changedFiles("", 1, "o", "r", "base1", "head1");

        assertEquals("/repos/o/r/compare/base1...head1", stub.uri(0));
        assertEquals(List.of("a.txt", "b/c.txt"), files, "空文件名要跳过");
    }

    @Test
    void rawFileDecodesBase64FromContentsApi() {
        String content = Base64.getMimeEncoder().encodeToString("中文内容".getBytes(StandardCharsets.UTF_8));
        stub.respondWith("{\"encoding\":\"base64\",\"content\":\"" + content + "\"}");

        String text = stub.newClient().rawFile("", 1, "o", "r", "main", "src/A.java");

        assertEquals("/repos/o/r/contents/src/A.java?ref=main", stub.uri(0));
        assertEquals("中文内容", text);
    }

    @Test
    void rawFileFallsBackToRawBaseWhenContentIsEmpty() {
        stub.respondByPath(exchange -> {
            if (exchange.getRequestURI().getPath().startsWith("/repos/")) {
                // 大文件：contents 的 content 为空（宿主已知行为），必须回退 raw
                return "{\"encoding\":\"base64\",\"content\":\"\"}";
            }
            return "raw text body";
        });

        String text = stub.newClient().rawFile("", 1, "o", "r", "main", "big.bin");

        assertEquals(2, stub.requestUris().size(), "contents 取不到内容时要回退 raw：实际 " + stub.requestUris());
        assertEquals("/repos/o/r/contents/big.bin?ref=main", stub.uri(0));
        assertEquals("/raw/o/r/main/big.bin", stub.uri(1));
        assertEquals("raw text body", text);
    }

    @Test
    void tokenBecomesBearerHeaderAndCredentialTypeTwoBecomesBasic() {
        stub.respondWith("[]");

        stub.newClient().branches("tok-1", 1, "o", "r");
        stub.newClient().branches("user:pass", 2, "o", "r");

        assertEquals("Bearer tok-1", stub.authHeaders().get(0));
        assertEquals("Basic " + Base64.getEncoder().encodeToString("user:pass".getBytes(StandardCharsets.UTF_8)),
                stub.authHeaders().get(1));
    }

    @Test
    void fallsBackToConfiguredTokenAndOmitsHeaderWhenBothBlank() {
        stub.respondWith("[]");
        GitHubProperties github = new GitHubProperties();
        github.setToken("config-token");

        new GitHubClient(github, stub.gitProperties(), new GitCache(16, 16, 30)).branches("", 1, "o", "r");
        stub.newClient().branches("", 1, "o", "r");

        assertEquals("Bearer config-token", stub.authHeaders().get(0), "项目未填令牌时用配置兜底");
        assertNull(stub.authHeaders().get(1), "两者都为空则匿名请求，不要发空 Authorization 头");
    }

    @Test
    void apiBaseIsConfigurableAndTrailingSlashIsStripped() {
        stub.respondWith("[]");
        GitProperties props = stub.gitProperties();
        props.setApiBase(stub.base() + "/"); // 故意多写一个尾斜杠

        new GitHubClient(new GitHubProperties(), props, new GitCache(16, 16, 30)).branches("", 1, "o", "r");

        assertEquals("/repos/o/r/branches", stub.uri(0), "不能拼出 //repos/...");
    }

    @Test
    void blankApiBaseFailsLoudlyInsteadOfHittingSomeDefault() {
        GitProperties props = new GitProperties();
        props.setApiBase("   ");

        IllegalStateException e = assertThrows(IllegalStateException.class, props::normalizedApiBase);

        assertTrue(e.getMessage().contains("git.api-base"), "报错要点名是哪个配置项：" + e.getMessage());
    }

    @Test
    void defaultsMatchTheHistoricalHardcodedValues() {
        GitProperties props = new GitProperties();

        assertEquals("https://api.github.com", props.normalizedApiBase());
        assertEquals("https://raw.githubusercontent.com", props.normalizedRawBase());
    }
}
