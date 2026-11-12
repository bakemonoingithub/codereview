package com.codereview.git;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PRA-01 双宿主 Webhook 契约与验证基线测试。
 * 验证 GitHub 与 Gitea 1.16.1 的固定验证样本、请求头规范、HMAC-SHA256 验签算法及字段抽取适配。
 */
class PrAutoReviewContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private JsonNode readFixture(String path) throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(path)) {
            assertNotNull(in, "未找到测试样本资源: " + path);
            return objectMapper.readTree(in);
        }
    }

    private String readFixtureString(String path) throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(path)) {
            assertNotNull(in, "未找到测试样本资源: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String hmacSha256Hex(String secret, byte[] payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(payload));
    }

    private static boolean verifySignature(String hostType, String secret, byte[] payload, String signatureHeader) throws Exception {
        if (signatureHeader == null || signatureHeader.isBlank() || secret == null) {
            return false;
        }
        String expectedHex = hmacSha256Hex(secret, payload);
        String actualHex = signatureHeader.trim();
        if ("github".equalsIgnoreCase(hostType)) {
            if (!actualHex.startsWith("sha256=")) {
                return false;
            }
            actualHex = actualHex.substring("sha256=".length());
        }
        return MessageDigest.isEqual(
                expectedHex.toLowerCase().getBytes(StandardCharsets.UTF_8),
                actualHex.toLowerCase().getBytes(StandardCharsets.UTF_8)
        );
    }

    @Test
    @DisplayName("HMAC-SHA256 验签契约：支持 GitHub sha256= 前缀与 Gitea 裸摘要，篡改 payload 拒绝")
    void signatureVerificationContract() throws Exception {
        String secret = "test-webhook-secret-2026";
        byte[] payload = "{\"repository\":{\"id\":31},\"sender\":{\"id\":201}}".getBytes(StandardCharsets.UTF_8);
        byte[] tampered = "{\"repository\":{\"id\":31},\"sender\":{\"id\":999}}".getBytes(StandardCharsets.UTF_8);

        String hex = hmacSha256Hex(secret, payload);
        assertEquals(64, hex.length());

        // GitHub 签名格式带有 sha256= 前缀
        String githubSig = "sha256=" + hex;
        assertTrue(verifySignature("github", secret, payload, githubSig));
        assertFalse(verifySignature("github", secret, tampered, githubSig), "篡改 payload 必须拒绝");
        assertFalse(verifySignature("github", "wrong-secret", payload, githubSig), "错误密钥必须拒绝");
        assertFalse(verifySignature("github", secret, payload, hex), "GitHub 缺失前缀必须拒绝");

        // Gitea 签名格式为裸 64 位十六进制
        assertTrue(verifySignature("gitea", secret, payload, hex));
        assertFalse(verifySignature("gitea", secret, tampered, hex), "篡改 payload 必须拒绝");
        assertFalse(verifySignature("gitea", "wrong-secret", payload, hex), "错误密钥必须拒绝");
    }

    @Test
    @DisplayName("GitHub 样本校验：新建 PR 事件提取仓库、PR 序号、源提交、目标分支与触发人")
    void gitHubPullRequestOpenedSample() throws Exception {
        JsonNode root = readFixture("fixtures/pra/github/pull_request_opened.json");

        assertEquals("opened", root.path("action").asText());
        assertEquals(15, root.path("number").asLong());
        assertEquals(31, root.path("repository").path("id").asLong());
        assertEquals("engineering/aiCodeReview", root.path("repository").path("full_name").asText());

        JsonNode pr = root.path("pull_request");
        assertEquals("open", pr.path("state").asText());
        assertEquals("main", pr.path("base").path("ref").asText());
        assertEquals("9da674bc34987d6acbec4f8521953315ecdb7d20", pr.path("head").path("sha").asText());
        assertEquals(201, root.path("sender").path("id").asLong());
        assertEquals("developer", root.path("sender").path("login").asText());
    }

    @Test
    @DisplayName("GitHub 样本校验：PR 普通评论 /recheck 提取 PR 关联与评论正文")
    void gitHubIssueCommentRecheckSample() throws Exception {
        JsonNode root = readFixture("fixtures/pra/github/issue_comment_recheck.json");

        assertEquals("created", root.path("action").asText());
        assertEquals(31, root.path("repository").path("id").asLong());
        assertEquals(15, root.path("issue").path("number").asLong());
        assertTrue(root.path("issue").hasNonNull("pull_request"), "PR 评论必须包含 issue.pull_request 节点");
        assertEquals(5001, root.path("comment").path("id").asLong());
        assertEquals("/recheck", root.path("comment").path("body").asText().trim());
        assertEquals(101, root.path("sender").path("id").asLong());
        assertEquals("li.ming", root.path("sender").path("login").asText());
    }

    @Test
    @DisplayName("GitHub 样本校验：普通 Issue 评论无 issue.pull_request，应识别为跳过")
    void gitHubIssueCommentRegularIssueSample() throws Exception {
        JsonNode root = readFixture("fixtures/pra/github/issue_comment_regular_issue.json");

        assertEquals("created", root.path("action").asText());
        assertEquals(88, root.path("issue").path("number").asLong());
        assertFalse(root.path("issue").hasNonNull("pull_request"), "普通 Issue 评论不应包含 pull_request 节点");
    }

    @Test
    @DisplayName("GitHub 样本校验：追加提交与机器人回写识别")
    void gitHubSynchronizeAndBotSample() throws Exception {
        JsonNode sync = readFixture("fixtures/pra/github/pull_request_synchronize.json");
        assertEquals("synchronize", sync.path("action").asText());
        assertEquals(15, sync.path("number").asLong());

        JsonNode bot = readFixture("fixtures/pra/github/issue_comment_bot.json");
        assertEquals(901, bot.path("sender").path("id").asLong());
        assertEquals("code-review-bot", bot.path("sender").path("login").asText());
    }

    @Test
    @DisplayName("Gitea 样本校验：新建 PR 事件提取仓库、PR 序号、源提交、目标分支与触发人")
    void giteaPullRequestOpenedSample() throws Exception {
        JsonNode root = readFixture("fixtures/pra/gitea/pull_request_opened.json");

        assertEquals("opened", root.path("action").asText());
        assertEquals(13, root.path("number").asLong());
        assertEquals(31, root.path("repository").path("id").asLong());
        assertEquals("engineering/aiCodeReview", root.path("repository").path("full_name").asText());

        JsonNode pr = root.path("pull_request");
        assertEquals("open", pr.path("state").asText());
        assertEquals("main", pr.path("base").path("ref").asText());
        assertEquals("59abb71e9341934c8fc9183c23c4f3653f24d879", pr.path("head").path("sha").asText());
        assertEquals(201, root.path("sender").path("id").asLong());
        assertEquals("developer", root.path("sender").path("login").asText());
    }

    @Test
    @DisplayName("Gitea 样本校验：PR 评论 /recheck 确认 is_pull 与评论正文")
    void giteaPullRequestCommentRecheckSample() throws Exception {
        JsonNode root = readFixture("fixtures/pra/gitea/pull_request_comment_recheck.json");

        assertEquals("created", root.path("action").asText());
        assertTrue(root.path("is_pull").asBoolean(), "Gitea PR 评论事件必须包含 is_pull = true");
        assertEquals(13, root.path("issue").path("number").asLong());
        assertEquals(3213, root.path("comment").path("id").asLong());
        assertEquals("/recheck", root.path("comment").path("body").asText().trim());
        assertEquals(101, root.path("sender").path("id").asLong());
        assertEquals("li.ming", root.path("sender").path("login").asText());
    }

    @Test
    @DisplayName("Gitea 样本校验：追加提交与机器人回写识别")
    void giteaSyncAndBotSample() throws Exception {
        JsonNode sync = readFixture("fixtures/pra/gitea/pull_request_sync.json");
        assertEquals("synchronized", sync.path("action").asText());
        assertEquals(13, sync.path("number").asLong());

        JsonNode bot = readFixture("fixtures/pra/gitea/pull_request_comment_bot.json");
        assertEquals(901, bot.path("sender").path("id").asLong());
        assertEquals("code-review-bot", bot.path("sender").path("login").asText());
    }

    @Test
    @DisplayName("业务幂等键契约：首次审查与评论复审分别计算 SHA-256 唯一摘要")
    void businessKeyContract() throws Exception {
        // 首次审查格式: [host_type, host_instance_key, repo_id, pr_number, "FIRST"]
        String firstVector = objectMapper.writeValueAsString(new Object[]{"github", "github.com", 31, 15, "FIRST"});
        MessageDigest md1 = MessageDigest.getInstance("SHA-256");
        String key1 = "pr-auto-review:" + HexFormat.of().formatHex(md1.digest(firstVector.getBytes(StandardCharsets.UTF_8)));
        assertTrue(key1.startsWith("pr-auto-review:"));
        assertEquals(15 + 64, key1.length());

        // 评论复审格式: [host_type, host_instance_key, repo_id, pr_number, "RECHECK", comment_id]
        String recheckVector = objectMapper.writeValueAsString(new Object[]{"github", "github.com", 31, 15, "RECHECK", 5001});
        MessageDigest md2 = MessageDigest.getInstance("SHA-256");
        String key2 = "pr-auto-review:" + HexFormat.of().formatHex(md2.digest(recheckVector.getBytes(StandardCharsets.UTF_8)));
        assertTrue(key2.startsWith("pr-auto-review:"));
        assertEquals(15 + 64, key2.length());
        assertFalse(key1.equals(key2), "首次审查与复审应生成不同业务键");
    }
}
