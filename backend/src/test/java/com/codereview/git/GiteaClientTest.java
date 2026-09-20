package com.codereview.git;

import com.codereview.common.BusinessException;
import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static com.codereview.git.GitStubServer.StubResponse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gitea 1.16.1 的 HTTP 契约测试。
 *
 * <p>锁的都是**与 GitHub 不同、照抄就会错**的行为：
 * <ul>
 *   <li>文件树单页 1000 条且必须翻页（不翻会静默丢文件）；</li>
 *   <li>列表用 {@code limit}（上限 50）而不是 {@code per_page}；</li>
 *   <li>分支哈希字段是 {@code commit.id}；</li>
 *   <li>含 {@code /} 的分支不能放进路径段，要先解析成 sha；</li>
 *   <li>API 根从仓库地址推导（含 {@code /gitea} 子路径），且 {@code Link} 头只当信号不 follow。</li>
 * </ul>
 */
class GiteaClientTest {

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

    /** 指向本桩的 Gitea 客户端（显式 api-base 覆盖，避免依赖推导）。 */
    private GiteaClient newClient() {
        return newClient(stub.base());
    }

    private GiteaClient newClient(String apiBase) {
        GiteaProperties props = new GiteaProperties();
        props.setApiBase(apiBase);
        props.setHosts(List.of("127.0.0.1"));
        return new GiteaClient(props, new GitCache(16, 16, 30));
    }

    private static GitRepoRef repo(String owner, String name) {
        return new GitRepoRef("http://stub.invalid", owner, name);
    }

    // ---------------------------------------------------------------- 文件树

    @Test
    void treeUsesRecursiveTrueAndFetchesEveryPage() {
        // 第一页回满 1000 条（Gitea 的单页上限），第二页只剩 3 条
        stub.respond(exchange -> {
            String page = query(exchange, "page");
            return StubResponse.ok("1".equals(page) ? treeJson(1000) : treeJson(3));
        });

        List<GitTreeEntry> entries = newClient().tree("", 1, repo("o", "r"), "dev");

        assertEquals(1003, entries.size(), "第二页必须被取到，否则大仓库会静默丢文件");
        assertEquals("/repos/o/r/git/trees/dev?recursive=true&per_page=1000&page=1", stub.uri(0));
        assertEquals("/repos/o/r/git/trees/dev?recursive=true&per_page=1000&page=2", stub.uri(1));
    }

    @Test
    void treeKeepsOnlyBlobAndTreeEntries() {
        stub.respondWith("""
                {"sha":"t","truncated":false,"tree":[
                  {"path":"src","type":"tree"},
                  {"path":"src/A.java","type":"blob"},
                  {"path":"sub","type":"commit"}
                ]}
                """);

        List<GitTreeEntry> entries = newClient().tree("", 1, repo("o", "r"), "main");

        assertEquals(List.of(new GitTreeEntry("src", "tree"), new GitTreeEntry("src/A.java", "blob")), entries);
    }

    @Test
    void treeResolvesSlashedBranchToShaBeforeRequestingPath() {
        stub.respond(exchange -> {
            if (exchange.getRequestURI().getPath().endsWith("/commits")) {
                return StubResponse.ok("[{\"sha\":\"abc1234\",\"commit\":{\"message\":\"m\"}}]");
            }
            return StubResponse.ok(treeJson(1));
        });

        newClient().tree("", 1, repo("o", "r"), "experiment/ai-gateway");

        assertEquals("/repos/o/r/commits?sha=experiment/ai-gateway&page=1&limit=1", stub.uri(0));
        assertEquals("/repos/o/r/git/trees/abc1234?recursive=true&per_page=1000&page=1", stub.uri(1),
                "含斜杠的分支名不能出现在路径段里（chi 路由按单段匹配）");
    }

    // ---------------------------------------------------------------- 分支与提交

    @Test
    void headCommitShaReadsCommitIdNotSha() {
        stub.respondWith("{\"name\":\"dev\",\"commit\":{\"id\":\"d658059ff6b7e875e7c4cbe320ead79846365003\"}}");

        String sha = newClient().headCommitSha("", 1, repo("o", "r"), "dev");

        assertEquals("/repos/o/r/branches/dev", stub.uri(0));
        assertEquals("d658059ff6b7e875e7c4cbe320ead79846365003", sha);
    }

    @Test
    void headCommitShaForSlashedBranchUsesCommitsQueryEndpoint() {
        stub.respondWith("[{\"sha\":\"deadbeef\",\"commit\":{\"message\":\"m\"}}]");

        String sha = newClient().headCommitSha("", 1, repo("o", "r"), "feature/x");

        assertEquals("/repos/o/r/commits?sha=feature/x&page=1&limit=1", stub.uri(0));
        assertEquals("deadbeef", sha);
    }

    @Test
    void branchesPaginatesWithLimitAndTotalCount() {
        stub.respond(exchange -> {
            String page = query(exchange, "page");
            int count = "1".equals(page) ? 50 : 1;
            return StubResponse.ok(branchJson(count, "1".equals(page) ? 0 : 50))
                    .withHeader("X-Total-Count", "51");
        });

        List<String> names = newClient().branches("", 1, repo("o", "r"));

        assertEquals(51, names.size());
        assertEquals("/repos/o/r/branches?page=1&limit=50", stub.uri(0));
        assertEquals("/repos/o/r/branches?page=2&limit=50", stub.uri(1));
    }

    @Test
    void commitPageUsesLimitAndClampsToFifty() {
        stub.respond(exchange -> StubResponse.ok("[]").withHeader("X-Total-Count", "10"));

        newClient().commitPage("", 1, repo("o", "r"), "dev", 2, 500);

        assertEquals("/repos/o/r/commits?sha=dev&page=2&limit=50", stub.uri(0),
                "Gitea 只认 limit（per_page 会被忽略并退化成默认 30）");
    }

    @Test
    void commitPageHasMoreComesFromLinkOrTotalCount() {
        stub.respond(exchange -> StubResponse.ok("[]").withHeader("X-Total-Count", "120"));
        assertTrue(newClient().commitPage("", 1, repo("o", "r"), "dev", 1, 50).hasMore());
        assertTrue(newClient().commitPage("", 1, repo("o", "r"), "dev", 2, 50).hasMore());
        assertFalse(newClient().commitPage("", 1, repo("o", "r"), "dev", 3, 50).hasMore(),
                "page*limit 达到总数即到底");
    }

    @Test
    void linkHeaderIsOnlyASignalNeverFollowed() {
        // Gitea 用 ROOT_URL 拼 Link，内网常配成 localhost/错误端口 —— 只能当"还有下一页"用
        stub.respond(exchange -> StubResponse.ok("[]")
                .withHeader("Link", "<http://wrong-host:9999/api/v1/repos/o/r/commits?page=2>; rel=\"next\""));

        CommitPage page = newClient().commitPage("", 1, repo("o", "r"), "dev", 1, 50);

        assertTrue(page.hasMore());
        assertEquals(1, stub.requestUris().size(), "绝不能去请求 Link 里的绝对 URL");
    }

    // ---------------------------------------------------------------- 文件内容

    @Test
    void rawFileDecodesBase64Contents() {
        String content = Base64.getMimeEncoder().encodeToString("中文内容".getBytes(StandardCharsets.UTF_8));
        stub.respondWith("{\"encoding\":\"base64\",\"content\":\"" + content + "\"}");

        String text = newClient().rawFile("", 1, repo("o", "r"), "dev", "src/A.java");

        assertEquals("/repos/o/r/contents/src/A.java?ref=dev", stub.uri(0));
        assertEquals("中文内容", text);
    }

    @Test
    void rawFileFallsBackToGiteaRawApiWhenContentIsBlank() {
        stub.respond(exchange -> exchange.getRequestURI().getPath().contains("/contents/")
                // 超过 10MiB 的文件：Gitea 仍回 base64，但 content 为空
                ? StubResponse.ok("{\"encoding\":\"base64\",\"content\":\"\"}")
                : StubResponse.ok("raw text body"));

        String text = newClient().rawFile("", 1, repo("o", "r"), "dev", "big.bin");

        assertEquals(2, stub.requestUris().size());
        assertEquals("/repos/o/r/raw/big.bin?ref=dev", stub.uri(1),
                "Gitea 的 raw 不是独立域名，就在 API 根下面");
        assertEquals("raw text body", text);
    }

    // ---------------------------------------------------------------- API 根与认证

    @Test
    void apiBaseIsDerivedFromRepoUrlIncludingSubPath() {
        GiteaProperties props = new GiteaProperties();
        props.setHosts(List.of("127.0.0.1"));
        // apiBase 留空 → 按仓库地址推导
        GiteaClient client = new GiteaClient(props, new GitCache(16, 16, 30));
        GitRepoRef subPathRepo = GitRepoRef.parse(stub.base() + "/gitea/team/repo");
        stub.respondWith("{\"commit\":{\"id\":\"x\"}}");

        client.headCommitSha("", 1, subPathRepo, "main");

        assertEquals("/gitea/api/v1/repos/team/repo/branches/main", stub.uri(0),
                "内网 Gitea 挂在 /gitea 子路径下，前缀不能丢");
    }

    @Test
    void configuredApiBaseOverridesDerivation() {
        stub.respondWith("{\"commit\":{\"id\":\"x\"}}");

        newClient(stub.base() + "/custom").headCommitSha("", 1, repo("o", "r"), "main");

        assertEquals("/custom/repos/o/r/branches/main", stub.uri(0));
    }

    @Test
    void sshFormWithoutApiBaseFailsLoudlyInsteadOfGuessing() {
        GiteaProperties props = new GiteaProperties();
        props.setHosts(List.of("gitea.example.com"));
        GiteaClient client = new GiteaClient(props, new GitCache(16, 16, 30));

        BusinessException e = assertThrows(BusinessException.class,
                () -> client.branches("", 1, GitRepoRef.parse("git@gitea.example.com:team/proj.git")));

        assertTrue(e.getMessage().contains("git.gitea.api-base"), "报错要点名该配哪个键：" + e.getMessage());
    }

    @Test
    void tokenUsesGiteaPrefixAndCredentialTypeTwoBecomesBasic() {
        stub.respond(exchange -> StubResponse.ok("[]").withHeader("X-Total-Count", "0"));

        newClient().branches("tok-1", 1, repo("o", "r"));
        newClient().branches("user:pass", 2, repo("o", "r"));

        assertEquals("token tok-1", stub.authHeaders().get(0));
        assertEquals("Basic " + Base64.getEncoder().encodeToString("user:pass".getBytes(StandardCharsets.UTF_8)),
                stub.authHeaders().get(1));
    }

    @Test
    void fallsBackToConfiguredTokenAndOmitsHeaderWhenBothBlank() {
        stub.respond(exchange -> StubResponse.ok("[]").withHeader("X-Total-Count", "0"));
        GiteaProperties props = new GiteaProperties();
        props.setApiBase(stub.base());
        props.setToken("config-token");

        new GiteaClient(props, new GitCache(16, 16, 30)).branches("", 1, repo("o", "r"));
        newClient().branches("", 1, repo("o", "r"));

        assertEquals("token config-token", stub.authHeaders().get(0));
        assertNull(stub.authHeaders().get(1), "两者都为空则匿名请求，不要发空 Authorization 头");
    }

    // ---------------------------------------------------------------- helpers

    private static String query(HttpExchange exchange, String name) {
        String q = exchange.getRequestURI().getQuery();
        if (q == null) {
            return "";
        }
        for (String pair : q.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) {
                return pair.substring(eq + 1);
            }
        }
        return "";
    }

    private static String treeJson(int count) {
        StringBuilder sb = new StringBuilder("{\"tree\":[");
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"path\":\"f").append(i).append(".java\",\"type\":\"blob\"}");
        }
        return sb.append("]}").toString();
    }

    private static String branchJson(int count, int offset) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"name\":\"b").append(offset + i).append("\",\"commit\":{\"id\":\"x\"}}");
        }
        return sb.append(']').toString();
    }

    /** 便于将来扩展断言时列出实际请求。 */
    @SuppressWarnings("unused")
    private List<String> uris() {
        return new ArrayList<>(stub.requestUris());
    }
}
