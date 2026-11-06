package com.codereview.git;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** GitHub 与 Gitea 1.16.1 的 PR 列表和详情只读调用。不请求 files 或 .diff。 */
class PullRequestClientTest {

    private GitStubServer stub;

    @BeforeEach
    void setUp() throws Exception {
        stub = new GitStubServer();
        stub.start();
    }

    @AfterEach
    void tearDown() {
        stub.stop();
    }

    @Test
    void gitHubListsOpenPullsFilteredByTargetBranchAndReadsOnePull() {
        stub.respond(exchange -> {
            String uri = exchange.getRequestURI().toString();
            if (uri.contains("/pulls/15")) {
                return GitStubServer.StubResponse.ok(pull("15", "acme/order", "acme/order"));
            }
            return GitStubServer.StubResponse.ok("[" + pull("15", "acme/order", "acme/order") + "]")
                    .withHeader("Link", "<https://example/pulls?page=2>; rel=\"next\"");
        });

        GitHubClient client = stub.newClient();
        GitRepoRef repo = GitStubServer.repo("acme", "order");
        PullRequests.Page page = client.pullRequests("token", 1, repo, "open", "dev", 2, 30);
        PullRequests.Item detail = client.pullRequest("token", 1, repo, 15);

        assertEquals("/repos/acme/order/pulls?state=open&sort=updated&direction=desc&page=2&per_page=30&base=dev",
                stub.uri(0));
        assertTrue(page.hasMore());
        assertEquals(15, page.items().get(0).number());
        assertEquals("feature", page.items().get(0).sourceBranch());
        assertEquals("dev", page.items().get(0).targetBranch());
        assertEquals("/repos/acme/order/pulls/15", stub.uri(1));
        assertEquals("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", detail.sourceSha());
        assertEquals("github", client.pullRequestProvider());
        assertFalse(stub.requestUris().stream().anyMatch(uri -> uri.contains("/files") || uri.endsWith(".diff")));
    }

    @Test
    void giteaUsesLimitAndFiltersTheCurrentPageWithoutFollowingLinkUrls() {
        stub.respond(exchange -> GitStubServer.StubResponse.ok("["
                        + pull("7", "team/app", "team/app") + ","
                        + pullOnBranch("8", "release") + "]")
                .withHeader("X-Total-Count", "80"));
        GiteaProperties properties = new GiteaProperties();
        properties.setApiBase(stub.base());
        properties.setHosts(List.of("stub.invalid"));
        GiteaClient client = new GiteaClient(properties, new GitCache(16, 16, 30), new GiteaGitMirror(properties));

        PullRequests.Page page = client.pullRequests("", 1, GitStubServer.repo("team", "app"),
                "open", "dev", 1, 100);

        assertEquals("/repos/team/app/pulls?state=open&page=1&limit=50", stub.uri(0));
        assertEquals(List.of(7L), page.items().stream().map(PullRequests.Item::number).toList());
        assertTrue(page.hasMore());
        assertEquals("gitea", client.pullRequestProvider());
    }

    private static String pull(String number, String headRepo, String baseRepo) {
        return pullOnBranch(number, "dev").replace("\"ref\":\"dev\"", "\"ref\":\"dev\"")
                .replace("\"full_name\":\"" + headRepo + "\"", "\"full_name\":\"" + headRepo + "\"");
    }

    private static String pullOnBranch(String number, String targetBranch) {
        return """
                {"number":%s,"title":"修复重试","state":"open","draft":false,"merged":false,
                "html_url":"https://example/%s","updated_at":"2026-11-06T00:00:00Z",
                "user":{"login":"chen"},
                "head":{"ref":"feature","sha":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                "repo":{"full_name":"team/app"}},
                "base":{"ref":"%s","sha":"BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB",
                "repo":{"full_name":"team/app"}}}
                """.formatted(number, number, targetBranch);
    }
}
