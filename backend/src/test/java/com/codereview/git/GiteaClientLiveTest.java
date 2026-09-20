package com.codereview.git;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对着**真机 Gitea 1.16.1** 跑的集成测试：默认跳过，只在本机起了 Gitea 时手动开启。
 *
 * <pre>
 * GITEA_TEST_BASE_URL=http://localhost:3000 \
 * GITEA_TEST_USER=gitea_admin \
 * GITEA_TEST_PASSWORD=... \
 *   mvnw test -Dtest=GiteaClientLiveTest
 * </pre>
 *
 * <p>为什么值得单写一份而不是只留桩测试：桩只能证明"我按调研结论拼了 URL"，
 * 证明不了"1.16.1 真的这么回"。文档里明确标注"未能确认"的几条（子路径下的 header 认证、
 * {@code commit.id} 字段、{@code limit} 上限、{@code .diff} 的文本形态、含斜杠分支）都靠它兜底。
 *
 * <p>默认不跑的另一个原因：它依赖一个真实的仓库与外网/内网环境，塞进 CI 会让整仓随机变红。
 */
@EnabledIfEnvironmentVariable(named = "GITEA_TEST_BASE_URL", matches = ".+")
class GiteaClientLiveTest {

    private static final String DEFAULT_REPO = "gitea_admin/codereview";

    private static GiteaClient client() {
        GiteaProperties props = new GiteaProperties();
        // 不设 api-base：走"从仓库地址推导"，顺带验证推导逻辑
        return new GiteaClient(props, new GitCache(16, 16, 30), new GiteaGitMirror(props));
    }

    private static GitRepoRef repo() {
        String base = env("GITEA_TEST_BASE_URL").replaceAll("/+$", "");
        String full = base + "/" + envOr("GITEA_TEST_REPO", DEFAULT_REPO);
        return GitRepoRef.parse(full);
    }

    /** 项目级 credential：credentialType=2 → Basic user:pass。 */
    private static String credential() {
        String user = env("GITEA_TEST_USER");
        String password = env("GITEA_TEST_PASSWORD");
        return user + ":" + password;
    }

    private static Integer credentialType() {
        return 2;
    }

    private static String env(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("缺少环境变量 " + name);
        }
        return value.trim();
    }

    private static String envOr(String name, String fallback) {
        String value = System.getenv(name);
        return (value == null || value.isBlank()) ? fallback : value.trim();
    }

    @Test
    void readsBranchesAndSlashedBranchHead() {
        List<String> branches = client().branches(credential(), credentialType(), repo());

        assertTrue(branches.contains("dev"), "至少要有默认分支 dev，实际：" + branches);
        String head = client().headCommitSha(credential(), credentialType(), repo(), "dev");
        assertTrue(head.matches("[0-9a-f]{40}"), "commit.id 必须是 40 位 sha，实际：" + head);

        // 含斜杠的分支：Gitea 的路径段不吃 /，实现要先解析成 sha（回归点）
        String slashed = branches.stream().filter(b -> b.contains("/")).findFirst().orElse(null);
        if (slashed != null) {
            String slashedHead = client().headCommitSha(credential(), credentialType(), repo(), slashed);
            assertTrue(slashedHead.matches("[0-9a-f]{40}"),
                    "分支 " + slashed + " 的 HEAD 解析失败，实际：" + slashedHead);
        }
    }

    @Test
    void readsTreePaginatedAndRawFile() {
        List<GitTreeEntry> tree = client().tree(credential(), credentialType(), repo(), "dev");

        assertTrue(tree.size() > 100, "文件树条目太少，可能只取到了第一页：" + tree.size());
        assertTrue(tree.stream().anyMatch(e -> "backend/pom.xml".equals(e.path())),
                "应包含 backend/pom.xml（可用 GITEA_TEST_REPO 指定其它仓库）");

        String pom = client().rawFile(credential(), credentialType(), repo(), "dev", "backend/pom.xml");
        assertTrue(pom.contains("<artifactId>backend</artifactId>"), "文件内容应能解码出来");
    }

    @Test
    void readsCommitPageWithLimitAndHasMore() {
        CommitPage page = client().commitPage(credential(), credentialType(), repo(), "dev", 1, 50);

        assertEquals(50, page.commits().size(), "Gitea 单页上限 50，应正好回满");
        assertTrue(page.hasMore(), "该仓库提交多于 50 条，必须有下一页信号");
        assertTrue(page.commits().get(0).sha().matches("[0-9a-f]{40}"));
        assertEquals(client().headCommitSha(credential(), credentialType(), repo(), "dev"),
                page.commits().get(0).sha(), "第一页第一条应等于分支 HEAD");
    }

    @Test
    void readsCommitDetailWithPerFilePatchesFromRawDiff() {
        String head = client().headCommitSha(credential(), credentialType(), repo(), "dev");

        CommitDetail detail = client().commitDetail(credential(), credentialType(), repo(), head);

        assertEquals(head, detail.sha());
        assertFalse(detail.files().isEmpty(), "最新提交应有变更文件");
        assertTrue(detail.files().stream().anyMatch(ChangedFile::hasPatch),
                "至少有一个文本文件能拿到 patch（Gitea 的 files[] 本身不含 patch，必须靠 .diff 切分）");
        assertTrue(detail.files().stream().filter(ChangedFile::hasPatch)
                        .allMatch(f -> f.patch().startsWith("@@")),
                "patch 口径应与 GitHub 一致（从 @@ 开始）");
    }

    @Test
    void comparesTwoCommitsThroughTheLocalMirror() {
        CommitPage page = client().commitPage(credential(), credentialType(), repo(), "dev", 1, 2);
        String head = page.commits().get(0).sha();
        String base = page.commits().get(1).sha();

        List<String> files = client().changedFiles(credential(), credentialType(), repo(), base, head);

        assertFalse(files.isEmpty(), "相邻两个提交之间应有变更文件");
    }
}
