package com.codereview.git;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 分支名含斜杠时的 URL 编码回归测试。
 *
 * <p>**bug 是怎么发现的**：给 `GitHubClient` 补上第一批 HTTP 测试（见 {@link GitHubClientTest}）时，
 * `headCommitSha("feature/x")` 断言 `…/branches/feature%2Fx` 实际收到 `…/branches/feature%252Fx`
 * —— `UriUtils.encodePathSegment` 先编成 `%2F`，Spring 把 String 当 URI 模板**又编了一遍**，
 * `%` 变成 `%25`。服务端解出来是字面量 `feature%2Fx`，**分支名含斜杠的请求必然 404**。
 *
 * <p>**为什么后果不小**：`ReviewService.resolveCommitSha` 对失败是"日志警告 + 返回 null"，
 * 于是记录里 `commit_sha` 静默为空、审查按**会移动的分支名**取内容 ——
 * 直接违背本项目"结果不可变、可复现"的前提。而 `feature/*`、`release/*` 这类分支极其常见。
 *
 * <p>修法：把已编码的 URL 以 {@link java.net.URI} 交给 RestClient（Spring 便不再插手），
 * 保证"编一次、发一次"。
 *
 * <p>**未验证项（如实标注）**：GitHub 服务端是否接受 `%2F` 作为这两个端点里的分支名，
 * 未做真机验证（当时匿名配额已耗尽，且无可用令牌）。本测试锁的是"客户端只编一次"，
 * 即恢复原代码 `encodePathSegment` 的**本意**；服务端接受度已登记在 backlog。
 */
class GitHubClientBranchEncodingTest {

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
    void slashInBranchIsEncodedExactlyOnceForHeadCommitSha() {
        stub.respondWith("{\"commit\":{\"sha\":\"deadbeef\"}}");

        stub.newClient().headCommitSha("", 1, "o", "r", "feature/x");

        assertEquals("/repos/o/r/branches/feature%2Fx", stub.uri(0),
                "多编一层会变成 feature%252Fx，服务端会去找一个名叫 feature%2Fx 的分支");
    }

    @Test
    void slashInRefsIsEncodedExactlyOnceForCompare() {
        stub.respondWith("{\"files\":[]}");

        stub.newClient().changedFiles("", 1, "o", "r", "release/1.0", "feature/x");

        assertEquals("/repos/o/r/compare/release%2F1.0...feature%2Fx", stub.uri(0));
    }

    @Test
    void slashInRefIsKeptLegalInQueryButNeverDoubleEncoded() {
        stub.respondWith("{\"encoding\":\"base64\",\"content\":\"aGk=\"}");

        stub.newClient().rawFile("", 1, "o", "r", "feature/x", "src/A.java");

        // 查询值里的 / 是合法字符（encodeQueryParam 有意不编它），关键是不能出现 %25 这种二次编码；
        // 路径段里的 / 必须编成 %2F，否则会被当成多一层路径
        assertEquals("/repos/o/r/contents/src/A.java?ref=feature/x", stub.uri(0));
        assertEquals(-1, stub.uri(0).indexOf("%25"), "任何位置都不该出现二次编码的 %25：" + stub.uri(0));
    }

    @Test
    void plainNamesAndShasAreUnchanged() {
        stub.respondWith("{\"files\":[]}");

        stub.newClient().changedFiles("", 1, "o", "r", "main", "abcdef1234567890");

        assertEquals("/repos/o/r/compare/main...abcdef1234567890", stub.uri(0),
                "没有特殊字符时不该被编码成别的样子（防回归）");
        assertEquals(1, stub.requestUris().size(), "不应额外发请求");
    }
}
