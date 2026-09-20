package com.codereview.git;

import com.codereview.common.BusinessException;
import com.codereview.common.ResultCode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 按 host 选实现（E1 最小骨架）。
 *
 * <p>回归背景：改造前 {@code GitRepoRef.host()} 在生产代码里**零使用**，
 * `GitHubClient` 是唯一实现且 base URL 写死 —— 填了内网地址的项目会被拿去查
 * {@code api.github.com}，报错信息里没有任何线索指向 host。
 *
 * <p>这里锁三条：命中就走对应实现；**未命中必须显式报错**（绝不回落）；
 * 两个实现抢同一个 host 要吵出来（配置错误不该靠运气）。
 */
class GitHostClientRegistryTest {

    /** 只关心"声明了哪些 host"的假实现；其余方法用不到。 */
    private static GitHostClient clientServing(String name, Set<String> hosts) {
        return new GitHostClient() {
            @Override
            public Set<String> hosts() {
                return hosts;
            }

            @Override
            public List<GitTreeEntry> tree(String t, Integer c, GitRepoRef ref, String b) {
                throw new UnsupportedOperationException(name);
            }

            @Override
            public String rawFile(String t, Integer c, GitRepoRef ref, String r, String p) {
                throw new UnsupportedOperationException(name);
            }

            @Override
            public String headCommitSha(String t, Integer c, GitRepoRef ref, String b) {
                throw new UnsupportedOperationException(name);
            }

            @Override
            public List<String> branches(String t, Integer c, GitRepoRef ref) {
                throw new UnsupportedOperationException(name);
            }

            @Override
            public List<CommitInfo> commits(String t, Integer c, GitRepoRef ref, String b) {
                throw new UnsupportedOperationException(name);
            }

            @Override
            public CommitPage commitPage(String t, Integer c, GitRepoRef ref, String b, int page, int perPage) {
                throw new UnsupportedOperationException(name);
            }

            @Override
            public List<String> changedFiles(String t, Integer c, GitRepoRef ref, String base, String head) {
                throw new UnsupportedOperationException(name);
            }

            @Override
            public CommitDetail commitDetail(String t, Integer c, GitRepoRef ref, String sha) {
                throw new UnsupportedOperationException(name);
            }
        };
    }

    private static GitHostClientRegistry registry(GitHostClient... clients) {
        return new GitHostClientRegistry(List.of(clients));
    }

    @Test
    void picksTheImplementationThatDeclaresTheHost() {
        GitHostClient github = clientServing("github", Set.of("github.com"));
        GitHostClient gitea = clientServing("gitea", Set.of("git.internal.corp"));

        assertSame(github, registry(github, gitea).forRepo(GitRepoRef.parse("https://github.com/o/r")));
        assertSame(gitea, registry(github, gitea).forRepo(GitRepoRef.parse("http://git.internal.corp/gitea/o/r")));
    }

    @Test
    void hostMatchingIsCaseInsensitiveAndTrimsThePortAwareHost() {
        GitHostClient github = clientServing("github", Set.of("github.com"));

        assertSame(github, registry(github).forRepo(GitRepoRef.parse("https://GitHub.com/o/r")));
    }

    @Test
    void unknownHostFailsLoudlyInsteadOfFallingBackToGithub() {
        GitHostClient github = clientServing("github", Set.of("github.com"));
        GitRepoRef internal = GitRepoRef.parse("http://192.104.224.172/gitea/team/repo");

        BusinessException e = assertThrows(BusinessException.class, () -> registry(github).forRepo(internal));

        assertEquals(ResultCode.GIT_HOST_UNSUPPORTED.getCode(), e.getCode());
        assertEquals(5003, e.getCode());
        assertTrue(e.getMessage().contains("192.104.224.172"), "要点名实际的 host：" + e.getMessage());
        assertTrue(e.getMessage().contains("github.com"), "也要说清当前已接入什么：" + e.getMessage());
    }

    @Test
    void unknownHostMessageIsReadableWhenNothingIsRegistered() {
        GitRepoRef internal = GitRepoRef.parse("http://gitea.local/team/repo");

        BusinessException e = assertThrows(BusinessException.class, () -> registry().forRepo(internal));

        assertEquals(5003, e.getCode());
        assertTrue(e.getMessage().contains("gitea.local"), "实际：" + e.getMessage());
    }

    @Test
    void twoImplementationsClaimingTheSameHostIsAConfigurationError() {
        GitHostClient a = clientServing("a", Set.of("github.com"));
        GitHostClient b = clientServing("b", Set.of("github.com"));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> registry(a, b).forRepo(GitRepoRef.parse("https://github.com/o/r")));

        assertTrue(e.getMessage().contains("github.com"), "实际：" + e.getMessage());
    }

    @Test
    void supportedHostsListsWhatIsWiredUp() {
        GitHostClient github = clientServing("github", Set.of("github.com"));
        GitHostClient gitea = clientServing("gitea", Set.of("git.internal.corp"));

        assertEquals(Set.of("github.com", "git.internal.corp"), registry(github, gitea).supportedHosts());
        assertEquals(Set.of("github.com"), registry(github).supportedHosts());
    }

    @Test
    void nullOrHostlessRefIsRejectedNotRouted() {
        GitHostClient github = clientServing("github", Set.of("github.com"));

        assertThrows(BusinessException.class, () -> registry(github).forRepo(null));
    }

    /** 真实现的声明要与注册表规则对得上：GitHub 只认 github.com。 */
    @Test
    void realGithubClientDeclaresExactlyGithubDotCom() {
        GitProperties props = new GitProperties();
        GitHubClient client = new GitHubClient(new GitHubProperties(), props, new GitCache(8, 8, 30));

        assertEquals(Set.of("github.com"), client.hosts());
        assertTrue(client.supports(GitRepoRef.parse("https://github.com/o/r")));
        assertTrue(!client.supports(GitRepoRef.parse("https://gitlab.com/o/r")));
        assertTrue(!client.supports(GitRepoRef.parse("http://127.0.0.1:3000/o/r")), "自建实例（含端口）不算 github.com");
    }
}
