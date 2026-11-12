package com.codereview.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.codereview.agent.api.ApiException;
import com.codereview.agent.api.ErrorCode;
import com.codereview.agent.db.AgentDatabase;
import com.codereview.agent.dto.PrAutoReviewDtos;
import com.codereview.agent.entity.AgentModelConfig;
import com.codereview.agent.entity.AgentProject;
import com.codereview.agent.entity.PrAutoReviewConfig;
import com.codereview.agent.entity.PrAutoReviewDelivery;
import com.codereview.agent.mapper.AgentModelConfigMapper;
import com.codereview.agent.mapper.AgentProjectMapper;
import com.codereview.agent.mapper.PrAutoReviewConfigMapper;
import com.codereview.agent.mapper.PrAutoReviewDeliveryMapper;
import com.codereview.git.GitHostClient;
import com.codereview.git.GitHostClientRegistry;
import com.codereview.git.GitRepoRef;
import com.codereview.git.GitUserRef;
import com.codereview.git.GiteaProperties;
import com.codereview.git.HostHttpException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PrAutoReviewServiceTest {

    private static final String PROJECT_ID = "01PROJECT00000000000000001";
    private static final String SECRET = "test-webhook-secret-2026";

    @Mock
    private AgentDatabase db;
    @Mock
    private GitHostClientRegistry registry;
    @Mock
    private AgentProjectMapper projectMapper;
    @Mock
    private PrAutoReviewConfigMapper configMapper;
    @Mock
    private PrAutoReviewDeliveryMapper deliveryMapper;
    @Mock
    private AgentModelConfigMapper modelConfigMapper;
    @Mock
    private GitHostClient gitHostClient;
    @Mock
    private GitHostClient giteaClient;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private PrAutoReviewService service;

    private AgentProject project;
    private PrAutoReviewConfig config;
    private PrAutoReviewDelivery storedDelivery;
    private GiteaProperties giteaProperties;

    @BeforeAll
    static void initializeMapperColumns() {
        MybatisConfiguration mybatis = new MybatisConfiguration();
        mybatis.addMapper(AgentProjectMapper.class);
        mybatis.addMapper(PrAutoReviewDeliveryMapper.class);
    }

    @BeforeEach
    void setUp() {
        giteaProperties = new GiteaProperties();
        service = new PrAutoReviewService(db, registry, objectMapper, giteaProperties);

        when(db.mapper(AgentProjectMapper.class)).thenReturn(projectMapper);
        when(db.mapper(PrAutoReviewConfigMapper.class)).thenReturn(configMapper);
        when(db.mapper(PrAutoReviewDeliveryMapper.class)).thenReturn(deliveryMapper);
        when(db.mapper(AgentModelConfigMapper.class)).thenReturn(modelConfigMapper);
        when(gitHostClient.pullRequestProvider()).thenReturn("github");
        when(giteaClient.pullRequestProvider()).thenReturn("gitea");
        when(registry.forRepo(any())).thenAnswer(call -> {
            GitRepoRef ref = call.getArgument(0);
            return "github.com".equalsIgnoreCase(ref.host()) ? gitHostClient : giteaClient;
        });
        when(db.inTransaction(any())).thenAnswer(call -> call.<Supplier<?>>getArgument(0).get());
        when(deliveryMapper.upsertReceived(any())).thenAnswer(call -> {
            storedDelivery = call.getArgument(0);
            return 1;
        });
        when(deliveryMapper.selectOne(any())).thenAnswer(call -> storedDelivery);

        project = new AgentProject();
        project.setId(PROJECT_ID);
        project.setName("aiCodeReview");
        project.setRepoUrl("https://github.com/engineering/aiCodeReview.git");
        project.setCurrentBranch("main");
        project.setIsDeleted(0);
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project);
        when(projectMapper.selectList(any())).thenReturn(List.of(project));

        config = new PrAutoReviewConfig();
        config.setId("01CONFIG00000000000000001");
        config.setProjectId(PROJECT_ID);
        config.setEnabled(1);
        config.setTargetBranch("main");
        config.setWebhookSecret(SECRET);
        config.setReviewers("[{\"id\":101,\"login\":\"li.ming\"}]");
        config.setBotActorId(901L);
        config.setBotLogin("code-review-bot");
        config.setRepoId(31L);
        config.setRepoFullName("engineering/aiCodeReview");
        config.setCreatedAt(LocalDateTime.now());
        config.setUpdatedAt(LocalDateTime.now());
        when(configMapper.selectOne(any())).thenReturn(config);
        when(configMapper.selectList(any())).thenReturn(List.of(config));
    }

    private byte[] loadFixtureBytes(String path) throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(path)) {
            assertNotNull(in, "未找到样本: " + path);
            return in.readAllBytes();
        }
    }

    private static String sign(String secret, byte[] payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(payload));
    }

    @Test
    @DisplayName("读取配置：未配置时返回默认关闭状态")
    void getConfigDefaultWhenNotPresent() {
        when(configMapper.selectOne(any())).thenReturn(null);

        PrAutoReviewDtos.ConfigView view = service.getConfig(PROJECT_ID);
        assertNotNull(view);
        assertFalse(view.enabled());
        assertEquals("main", view.targetBranch());
        assertFalse(view.webhookSecretConfigured());
        assertTrue(view.reviewers().isEmpty());
    }

    @Test
    @DisplayName("更新配置：保存目标分支、白名单与 Webhook 密钥")
    void updateConfigSuccess() {
        PrAutoReviewDtos.ConfigUpdate req = new PrAutoReviewDtos.ConfigUpdate(
                true,
                "dev",
                null,
                null,
                null,
                "https://review.example.com",
                "new-secret-xyz",
                List.of(new PrAutoReviewDtos.ReviewerItem(101L, "li.ming"), new PrAutoReviewDtos.ReviewerItem(102L, "zhang.san")),
                901L,
                "code-review-bot",
                31L,
                "engineering/aiCodeReview"
        );

        PrAutoReviewDtos.ConfigView updated = service.updateConfig(PROJECT_ID, req);
        assertNotNull(updated);
        assertTrue(updated.enabled());
        assertEquals("dev", updated.targetBranch());
        assertEquals(2, updated.reviewers().size());
        assertTrue(updated.webhookSecretConfigured());
    }

    @Test
    @DisplayName("更新配置：引用不存在的模型配置时拒绝")
    void updateConfigRejectsNonExistentModel() {
        when(modelConfigMapper.selectById("bad-model")).thenReturn(null);

        PrAutoReviewDtos.ConfigUpdate req = new PrAutoReviewDtos.ConfigUpdate(
                true, "main", "bad-model", null, null, null, null, null, null, null, null, null
        );

        ApiException ex = assertThrows(ApiException.class, () -> service.updateConfig(PROJECT_ID, req));
        assertEquals(ErrorCode.NOT_FOUND, ex.errorCode());
    }

    @Test
    void switchingToDefaultRulesClearsBothPromptReferences() {
        config.setPromptId("previous-prompt");
        config.setPromptVersionId("previous-version");
        PrAutoReviewDtos.ConfigUpdate req = new PrAutoReviewDtos.ConfigUpdate(
                true, "main", null, null, null, null, null, null, null, null, null, null);

        var updated = service.updateConfig(PROJECT_ID, req);

        assertNull(updated.promptId());
        assertNull(updated.promptVersionId());
        verify(configMapper).updateById(config);
    }

    @Test
    void rejectsHalfSpecifiedPromptSelection() {
        PrAutoReviewDtos.ConfigUpdate req = new PrAutoReviewDtos.ConfigUpdate(
                true, "main", null, "prompt", null, null, null, null, null, null, null, null);

        assertEquals(ErrorCode.VALIDATION_FAILED,
                assertThrows(ApiException.class, () -> service.updateConfig(PROJECT_ID, req)).errorCode());
        verify(configMapper, never()).updateById(any(PrAutoReviewConfig.class));
    }

    @Test
    @DisplayName("审核者查询：成功返回 GitUserRef，404 时抛出友好提示")
    void reviewerLookupSuccessAndNotFound() {
        GitUserRef user = new GitUserRef(101L, "li.ming", "Li Ming", "https://avatar.com/101");
        when(gitHostClient.findUserByUsername(any(), any(), any(), eq("li.ming"))).thenReturn(user);

        GitUserRef result = service.lookupReviewer(PROJECT_ID, "li.ming");
        assertEquals(101L, result.id());
        assertEquals("li.ming", result.login());

        when(gitHostClient.findUserByUsername(any(), any(), any(), eq("nobody")))
                .thenThrow(new HostHttpException(404, "Not Found", null, null));

        ApiException ex = assertThrows(ApiException.class, () -> service.lookupReviewer(PROJECT_ID, "nobody"));
        assertEquals(ErrorCode.NOT_FOUND, ex.errorCode());
        assertEquals("未找到此账号，或当前凭据无法查看", ex.getMessage());
    }

    @Test
    @DisplayName("GitHub 场景：PR opened 验签成功且目标分支匹配，判定为 RECEIVED / FIRST")
    void gitHubPullRequestOpenedSuccess() throws Exception {
        byte[] payload = loadFixtureBytes("fixtures/pra/github/pull_request_opened.json");
        String sig = "sha256=" + sign(SECRET, payload);

        PrAutoReviewDtos.WebhookReceiveResult result = service.receiveWebhook(
                "github", "github.com", "gh-del-001", "pull_request", null, sig, payload
        );

        assertNotNull(result);
        assertEquals("RECEIVED", result.decisionState());
        assertEquals(1, result.receiveCount());

        ArgumentCaptor<PrAutoReviewDelivery> captor = ArgumentCaptor.forClass(PrAutoReviewDelivery.class);
        verify(deliveryMapper).upsertReceived(captor.capture());
        PrAutoReviewDelivery saved = captor.getValue();
        assertEquals("github", saved.getHostType());
        assertEquals("gh-del-001", saved.getDeliveryId());
        assertEquals("pull_request", saved.getEventName());
        assertEquals("FIRST", saved.getTriggerKind());
        assertEquals("main", saved.getTargetBranch());
        assertEquals(31L, saved.getRepoId());
        assertEquals(15L, saved.getPrNumber());
    }

    @Test
    @DisplayName("GitHub 场景：PR opened 目标分支不匹配配置分支，判定为 SKIPPED / TARGET_BRANCH_MISMATCH")
    void gitHubPullRequestTargetBranchMismatch() throws Exception {
        config.setTargetBranch("release"); // 配置为 release，样本为 main
        byte[] payload = loadFixtureBytes("fixtures/pra/github/pull_request_opened.json");
        String sig = "sha256=" + sign(SECRET, payload);

        PrAutoReviewDtos.WebhookReceiveResult result = service.receiveWebhook(
                "github", "github.com", "gh-del-002", "pull_request", null, sig, payload
        );

        assertEquals("SKIPPED", result.decisionState());
        assertEquals("TARGET_BRANCH_MISMATCH", result.decisionCode());
    }

    @Test
    @DisplayName("GitHub 场景：审核者发表 /recheck 评论，判定为 RECEIVED / RECHECK")
    void gitHubIssueCommentRecheckSuccess() throws Exception {
        byte[] payload = loadFixtureBytes("fixtures/pra/github/issue_comment_recheck.json");
        String sig = "sha256=" + sign(SECRET, payload);

        PrAutoReviewDtos.WebhookReceiveResult result = service.receiveWebhook(
                "github", "github.com", "gh-del-003", "issue_comment", null, sig, payload
        );

        assertEquals("RECEIVED", result.decisionState());

        ArgumentCaptor<PrAutoReviewDelivery> captor = ArgumentCaptor.forClass(PrAutoReviewDelivery.class);
        verify(deliveryMapper).upsertReceived(captor.capture());
        PrAutoReviewDelivery saved = captor.getValue();
        assertEquals("RECHECK", saved.getTriggerKind());
        assertEquals(5001L, saved.getSourceCommentId());
        assertEquals(101L, saved.getActorUserId());
    }

    @Test
    @DisplayName("GitHub 场景：非审核者发表 /recheck 评论，判定为 SKIPPED / REVIEWER_NOT_ALLOWED")
    void gitHubReviewerNotAllowed() throws Exception {
        config.setReviewers("[{\"id\":888,\"login\":\"other.user\"}]");
        byte[] payload = loadFixtureBytes("fixtures/pra/github/issue_comment_recheck.json"); // sender.id is 101
        String sig = "sha256=" + sign(SECRET, payload);

        PrAutoReviewDtos.WebhookReceiveResult result = service.receiveWebhook(
                "github", "github.com", "gh-del-004", "issue_comment", null, sig, payload
        );

        assertEquals("SKIPPED", result.decisionState());
        assertEquals("REVIEWER_NOT_ALLOWED", result.decisionCode());
    }

    @Test
    void reviewerLoginDoesNotGrantAccessToADifferentId() throws Exception {
        ObjectNode root = (ObjectNode) objectMapper.readTree(loadFixtureBytes("fixtures/pra/github/issue_comment_recheck.json"));
        ((ObjectNode) root.path("sender")).put("id", 888);
        byte[] payload = objectMapper.writeValueAsBytes(root);

        var result = service.receiveWebhook("github", null, "different-id", "issue_comment", null,
                "sha256=" + sign(SECRET, payload), payload);

        assertEquals("REVIEWER_NOT_ALLOWED", result.decisionCode());
        assertNull(storedDelivery.getTriggerKind());
    }

    @Test
    @DisplayName("GitHub 场景：机器人发言自动跳过，判定为 SKIPPED / BOT_COMMENT")
    void gitHubBotCommentSkipped() throws Exception {
        byte[] payload = loadFixtureBytes("fixtures/pra/github/issue_comment_bot.json");
        String sig = "sha256=" + sign(SECRET, payload);

        PrAutoReviewDtos.WebhookReceiveResult result = service.receiveWebhook(
                "github", "github.com", "gh-del-005", "issue_comment", null, sig, payload
        );

        assertEquals("SKIPPED", result.decisionState());
        assertEquals("BOT_COMMENT", result.decisionCode());
    }

    @Test
    @DisplayName("GitHub 场景：普通 Issue 评论无 issue.pull_request，判定为 SKIPPED / ACTION_IGNORED")
    void gitHubRegularIssueCommentSkipped() throws Exception {
        byte[] payload = loadFixtureBytes("fixtures/pra/github/issue_comment_regular_issue.json");
        String sig = "sha256=" + sign(SECRET, payload);

        PrAutoReviewDtos.WebhookReceiveResult result = service.receiveWebhook(
                "github", "github.com", "gh-del-006", "issue_comment", null, sig, payload
        );

        assertEquals("SKIPPED", result.decisionState());
        assertEquals("ACTION_IGNORED", result.decisionCode());
    }

    @Test
    @DisplayName("GitHub 场景：追加提交 synchronize，判定为 SKIPPED / ACTION_IGNORED")
    void gitHubSynchronizeSkipped() throws Exception {
        byte[] payload = loadFixtureBytes("fixtures/pra/github/pull_request_synchronize.json");
        String sig = "sha256=" + sign(SECRET, payload);

        PrAutoReviewDtos.WebhookReceiveResult result = service.receiveWebhook(
                "github", "github.com", "gh-del-007", "pull_request", null, sig, payload
        );

        assertEquals("SKIPPED", result.decisionState());
        assertEquals("ACTION_IGNORED", result.decisionCode());
    }

    @Test
    @DisplayName("Gitea 场景：PR opened 裸十六进制验签成功，判定为 RECEIVED / FIRST")
    void giteaPullRequestOpenedSuccess() throws Exception {
        // Gitea 项目仓库地址匹配
        project.setRepoUrl("http://localhost:3000/gitea/engineering/aiCodeReview.git");
        byte[] payload = loadFixtureBytes("fixtures/pra/gitea/pull_request_opened.json");
        String sig = sign(SECRET, payload); // Gitea 签名不带 sha256= 前缀

        PrAutoReviewDtos.WebhookReceiveResult result = service.receiveWebhook(
                "gitea", null, "gta-del-001", "pull_request", "pull_request", sig, payload
        );

        assertEquals("RECEIVED", result.decisionState());
        ArgumentCaptor<PrAutoReviewDelivery> captor = ArgumentCaptor.forClass(PrAutoReviewDelivery.class);
        verify(deliveryMapper).upsertReceived(captor.capture());
        PrAutoReviewDelivery saved = captor.getValue();
        assertEquals("gitea", saved.getHostType());
        assertEquals("http://localhost:3000/gitea", saved.getHostInstanceKey());
        assertEquals("FIRST", saved.getTriggerKind());
        assertEquals(13L, saved.getPrNumber());
    }

    @Test
    @DisplayName("Gitea 场景：PR 评论 /recheck 确认 is_pull，判定为 RECEIVED / RECHECK")
    void giteaPullRequestCommentRecheckSuccess() throws Exception {
        project.setRepoUrl("http://localhost:3000/engineering/aiCodeReview.git");
        byte[] payload = loadFixtureBytes("fixtures/pra/gitea/pull_request_comment_recheck.json");
        String sig = sign(SECRET, payload);

        PrAutoReviewDtos.WebhookReceiveResult result = service.receiveWebhook(
                "gitea", null, "gta-del-002", "issue_comment", "pull_request_comment", sig, payload
        );

        assertEquals("RECEIVED", result.decisionState());
        ArgumentCaptor<PrAutoReviewDelivery> captor = ArgumentCaptor.forClass(PrAutoReviewDelivery.class);
        verify(deliveryMapper).upsertReceived(captor.capture());
        PrAutoReviewDelivery saved = captor.getValue();
        assertEquals("RECHECK", saved.getTriggerKind());
        assertEquals(3213L, saved.getSourceCommentId());
    }

    @Test
    @DisplayName("Gitea 场景：追加提交 pull_request_sync，判定为 SKIPPED / ACTION_IGNORED")
    void giteaSyncSkipped() throws Exception {
        project.setRepoUrl("http://localhost:3000/engineering/aiCodeReview.git");
        byte[] payload = loadFixtureBytes("fixtures/pra/gitea/pull_request_sync.json");
        String sig = sign(SECRET, payload);

        PrAutoReviewDtos.WebhookReceiveResult result = service.receiveWebhook(
                "gitea", null, "gta-del-003", "pull_request", "pull_request_sync", sig, payload
        );

        assertEquals("SKIPPED", result.decisionState());
        assertEquals("ACTION_IGNORED", result.decisionCode());
    }

    @Test
    void giteaRecheckRequiresBothCommentHeadersAndBooleanPrMarker() throws Exception {
        project.setRepoUrl("https://git.example/gitea/engineering/aiCodeReview");
        ObjectNode root = (ObjectNode) objectMapper.readTree(loadFixtureBytes("fixtures/pra/gitea/pull_request_comment_recheck.json"));
        byte[] payload = objectMapper.writeValueAsBytes(root);
        assertEquals("ACTION_IGNORED", service.receiveWebhook("gitea", null, "wrong-type", "issue_comment",
                "issue_comment", sign(SECRET, payload), payload).decisionCode());
        assertEquals("EVENT_NOT_SUPPORTED", service.receiveWebhook("gitea", null, "wrong-event", "push",
                "pull_request_comment", sign(SECRET, payload), payload).decisionCode());

        root.put("is_pull", false);
        byte[] issuePayload = objectMapper.writeValueAsBytes(root);
        assertEquals("ACTION_IGNORED", service.receiveWebhook("gitea", null, "wrong-marker", "issue_comment",
                "pull_request_comment", sign(SECRET, issuePayload), issuePayload).decisionCode());
        assertNull(storedDelivery.getTriggerKind());
    }

    @Test
    void giteaSshRepoUsesConfiguredApiSiteRoot() throws Exception {
        project.setRepoUrl("git@git.example:engineering/aiCodeReview.git");
        giteaProperties.setApiBase("https://git.example/gitea/api/v1/");
        byte[] payload = loadFixtureBytes("fixtures/pra/gitea/pull_request_opened.json");

        var result = service.receiveWebhook("gitea", null, "ssh-project", "pull_request", "pull_request",
                sign(SECRET, payload), payload);

        assertEquals("RECEIVED", result.decisionState());
        assertEquals("https://git.example/gitea", storedDelivery.getHostInstanceKey());
    }

    @Test
    void explicitInstanceSeparatesIdenticalRepoAndDeliveryIds() throws Exception {
        project.setRepoUrl("https://git.example/gitea-one/engineering/aiCodeReview");
        AgentProject secondProject = new AgentProject();
        secondProject.setId("another-project");
        secondProject.setRepoUrl("https://git.example/gitea-two/engineering/aiCodeReview");
        PrAutoReviewConfig secondConfig = new PrAutoReviewConfig();
        secondConfig.setProjectId(secondProject.getId());
        secondConfig.setRepoId(31L);
        secondConfig.setEnabled(1);
        secondConfig.setTargetBranch("main");
        secondConfig.setWebhookSecret("another-secret");
        when(projectMapper.selectList(any())).thenReturn(List.of(project, secondProject));
        when(configMapper.selectList(any())).thenReturn(List.of(config, secondConfig));
        byte[] payload = loadFixtureBytes("fixtures/pra/gitea/pull_request_opened.json");
        String firstSignature = sign(SECRET, payload);

        assertEquals("HOST_INSTANCE_INVALID", service.receiveWebhook("gitea", null, "same-id", "pull_request",
                "pull_request", firstSignature, payload).decisionCode());
        assertEquals("RECEIVED", service.receiveWebhook("gitea", "https://GIT.example:443/gitea-one/", "same-id",
                "pull_request", "pull_request", firstSignature, payload).decisionState());
        assertEquals("https://git.example/gitea-one", storedDelivery.getHostInstanceKey());
        assertEquals(PROJECT_ID, storedDelivery.getProjectId());

        assertEquals("SIGNATURE_INVALID", service.receiveWebhook("gitea", "https://git.example/gitea-two", "same-id",
                "pull_request", "pull_request", firstSignature, payload).decisionCode());
        assertEquals("RECEIVED", service.receiveWebhook("gitea", "https://git.example/gitea-two", "same-id",
                "pull_request", "pull_request", sign("another-secret", payload), payload).decisionState());
        assertEquals("https://git.example/gitea-two", storedDelivery.getHostInstanceKey());
        assertEquals(secondProject.getId(), storedDelivery.getProjectId());
    }

    @Test
    void repositoryIdContinuesToMatchAfterRename() throws Exception {
        ObjectNode root = (ObjectNode) objectMapper.readTree(loadFixtureBytes("fixtures/pra/github/pull_request_opened.json"));
        ((ObjectNode) root.path("repository")).put("full_name", "engineering/renamed");
        byte[] payload = objectMapper.writeValueAsBytes(root);

        var result = service.receiveWebhook("github", null, "renamed", "pull_request", null,
                "sha256=" + sign(SECRET, payload), payload);

        assertEquals("RECEIVED", result.decisionState());
        assertEquals(PROJECT_ID, storedDelivery.getProjectId());
        assertEquals("engineering/renamed", storedDelivery.getRepoFullName());
    }

    @Test
    void repositoryNameFallbackOnlyAppliesBeforeAnIdIsConfigured() throws Exception {
        ObjectNode root = (ObjectNode) objectMapper.readTree(loadFixtureBytes("fixtures/pra/github/pull_request_opened.json"));
        ((ObjectNode) root.path("repository")).put("id", 999);
        byte[] payload = objectMapper.writeValueAsBytes(root);
        String signature = "sha256=" + sign(SECRET, payload);
        ReflectionTestUtils.setField(service, "globalWebhookSecret", SECRET);

        var mismatched = service.receiveWebhook("github", null, "other-repo", "pull_request", null, signature, payload);
        assertEquals("PROJECT_NOT_BOUND", mismatched.decisionCode());
        assertNull(storedDelivery.getProjectId());

        config.setRepoId(null);
        var fallback = service.receiveWebhook("github", null, "repo-name", "pull_request", null, signature, payload);
        assertEquals("RECEIVED", fallback.decisionState());
        assertEquals(PROJECT_ID, storedDelivery.getProjectId());
    }

    @Test
    void conflictingProjectBindingsAreRecordedAfterSignatureVerification() throws Exception {
        AgentProject duplicateProject = new AgentProject();
        duplicateProject.setId("duplicate-project");
        duplicateProject.setRepoUrl("https://github.com/engineering/old-name");
        PrAutoReviewConfig duplicateConfig = new PrAutoReviewConfig();
        duplicateConfig.setProjectId(duplicateProject.getId());
        duplicateConfig.setRepoId(31L);
        duplicateConfig.setWebhookSecret("different-secret");
        when(projectMapper.selectList(any())).thenReturn(List.of(project, duplicateProject));
        when(configMapper.selectList(any())).thenReturn(List.of(config, duplicateConfig));
        byte[] payload = loadFixtureBytes("fixtures/pra/github/pull_request_opened.json");

        var result = service.receiveWebhook("github", null, "conflict", "pull_request", null,
                "sha256=" + sign(SECRET, payload), payload);

        assertEquals("CONFIG_CONFLICT", result.decisionCode());
        assertNull(storedDelivery.getProjectId());
    }

    @Test
    @DisplayName("验签失败场景：载荷篡改或密钥错误记录 REJECTED / SIGNATURE_INVALID，且不占去重键")
    void signatureInvalidRecordedAsRejected() throws Exception {
        byte[] payload = loadFixtureBytes("fixtures/pra/github/pull_request_opened.json");
        String wrongSig = "sha256=0000000000000000000000000000000000000000000000000000000000000000";

        PrAutoReviewDtos.WebhookReceiveResult result = service.receiveWebhook(
                "github", "github.com", "gh-del-tampered", "pull_request", null, wrongSig, payload
        );

        assertEquals("REJECTED", result.decisionState());
        assertEquals("SIGNATURE_INVALID", result.decisionCode());

        ArgumentCaptor<PrAutoReviewDelivery> captor = ArgumentCaptor.forClass(PrAutoReviewDelivery.class);
        verify(deliveryMapper).insert(captor.capture());
        PrAutoReviewDelivery saved = captor.getValue();
        assertEquals("REJECTED", saved.getDecisionState());
        assertEquals("SIGNATURE_INVALID", saved.getDecisionCode());
        assertNull(saved.getDeliveryId(), "未验证投递 ID 应留空，避免占用去重唯一键");
        assertNull(saved.getHostType(), "未验证宿主留空");
        assertNull(saved.getPayloadSnapshot(), "未验证快照留空");
    }

    @Test
    void missingDeliveryIdCannotOccupyAReplayKey() throws Exception {
        byte[] payload = loadFixtureBytes("fixtures/pra/github/pull_request_opened.json");

        var result = service.receiveWebhook("github", null, null, "pull_request", null,
                "sha256=" + sign(SECRET, payload), payload);

        assertEquals("DELIVERY_ID_INVALID", result.decisionCode());
        verify(deliveryMapper, never()).upsertReceived(any());
        ArgumentCaptor<PrAutoReviewDelivery> captor = ArgumentCaptor.forClass(PrAutoReviewDelivery.class);
        verify(deliveryMapper).insert(captor.capture());
        assertNull(captor.getValue().getDeliveryId());
        assertNull(captor.getValue().getPayloadSnapshot());
    }

    @Test
    void receivedAndLegacySnapshotsRemoveNestedCredentialFields() throws Exception {
        ObjectNode root = (ObjectNode) objectMapper.readTree(loadFixtureBytes("fixtures/pra/github/pull_request_opened.json"));
        ((ObjectNode) root.path("repository")).put("token", "fixture-token");
        root.putArray("extra").addObject().put("Authorization", "fixture-authorization").put("name", "retained");
        byte[] payload = objectMapper.writeValueAsBytes(root);

        service.receiveWebhook("github", null, "nested-credential", "pull_request", null,
                "sha256=" + sign(SECRET, payload), payload);
        assertSafeSnapshot(storedDelivery.getPayloadSnapshot());

        storedDelivery.setPayloadSnapshot(root.toString());
        assertSafeSnapshot(service.getDelivery(PROJECT_ID, storedDelivery.getId()).payloadSnapshot());
    }

    private void assertSafeSnapshot(String snapshot) throws Exception {
        var stored = objectMapper.readTree(snapshot);
        assertFalse(stored.path("repository").has("token"));
        assertFalse(stored.path("extra").path(0).has("Authorization"));
        assertEquals(31, stored.path("repository").path("id").asInt());
        assertEquals("retained", stored.path("extra").path(0).path("name").asText());
    }

    @Test
    @DisplayName("重复投递返回数据库原记录的状态与计数")
    void duplicateDeliveryReturnsPersistedState() throws Exception {
        byte[] payload = loadFixtureBytes("fixtures/pra/github/pull_request_opened.json");
        String sig = "sha256=" + sign(SECRET, payload);

        PrAutoReviewDelivery existing = new PrAutoReviewDelivery();
        existing.setId("01DELIVERY000000000000001");
        existing.setHostType("github");
        existing.setHostInstanceKey("github.com");
        existing.setDeliveryId("gh-del-dup-1");
        existing.setReceiveCount(2);
        existing.setDecisionState("PROCESSING");
        existing.setDecisionCode(null);

        when(deliveryMapper.selectOne(any())).thenReturn(existing);

        PrAutoReviewDtos.WebhookReceiveResult result = service.receiveWebhook(
                "github", "github.com", "gh-del-dup-1", "pull_request", null, sig, payload
        );

        assertEquals(existing.getId(), result.deliveryId());
        assertEquals("PROCESSING", result.decisionState());
        assertEquals(2, result.receiveCount());
        verify(deliveryMapper).upsertReceived(any());
        verify(deliveryMapper, never()).insert(any(PrAutoReviewDelivery.class));
        verify(deliveryMapper, never()).updateById(any(PrAutoReviewDelivery.class));
    }

    @Test
    @DisplayName("项目关闭场景：项目未启用自动审查时，标记为 SKIPPED / AUTO_REVIEW_DISABLED")
    void autoReviewDisabledSkipped() throws Exception {
        config.setEnabled(0);
        byte[] payload = loadFixtureBytes("fixtures/pra/github/pull_request_opened.json");
        String sig = "sha256=" + sign(SECRET, payload);

        PrAutoReviewDtos.WebhookReceiveResult result = service.receiveWebhook(
                "github", "github.com", "gh-del-disabled", "pull_request", null, sig, payload
        );

        assertEquals("SKIPPED", result.decisionState());
        assertEquals("AUTO_REVIEW_DISABLED", result.decisionCode());
    }
}
