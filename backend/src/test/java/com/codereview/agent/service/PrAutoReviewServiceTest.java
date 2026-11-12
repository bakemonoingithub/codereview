package com.codereview.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.codereview.agent.api.ApiException;
import com.codereview.agent.api.ErrorCode;
import com.codereview.agent.db.AgentDatabase;
import com.codereview.agent.dto.PrAutoReviewDtos;
import com.codereview.agent.dto.TaskDtos;
import com.codereview.agent.entity.AgentModelConfig;
import com.codereview.agent.entity.AgentProject;
import com.codereview.agent.entity.AgentReviewTask;
import com.codereview.agent.entity.PrAutoReviewConfig;
import com.codereview.agent.entity.PrAutoReviewDelivery;
import com.codereview.agent.mapper.AgentModelConfigMapper;
import com.codereview.agent.mapper.AgentProjectMapper;
import com.codereview.agent.mapper.AgentReviewTaskMapper;
import com.codereview.agent.mapper.PrAutoReviewConfigMapper;
import com.codereview.agent.mapper.PrAutoReviewDeliveryMapper;
import com.codereview.agent.repository.PullRequestViews;
import com.codereview.agent.repository.RepositoryReadService;
import com.codereview.agent.support.AgentTime;
import com.codereview.git.GitHostClient;
import com.codereview.git.GitHostClientRegistry;
import com.codereview.git.GitRepoRef;
import com.codereview.git.GitUserRef;
import com.codereview.git.GiteaProperties;
import com.codereview.git.HostHttpException;
import com.codereview.git.IssueComment;
import com.codereview.git.IssueCommentPage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.ibatis.mapping.BoundSql;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.ResourceAccessException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PrAutoReviewServiceTest {

    private static final String PROJECT_ID = "01PROJECT00000000000000001";
    private static final String SECRET = "test-webhook-secret-2026";
    private static final String SOURCE_HEAD = "9da674bc34987d6acbec4f8521953315ecdb7d20";
    private static final String BASE_HEAD = "f228b3da1c2ec0bf0cad8c6e3bb35b5bb7788b0b";
    private static final String TASK_ID = "01TASK00000000000000000001";
    private static MybatisConfiguration mybatis;

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
    private AgentReviewTaskMapper reviewTaskMapper;
    @Mock
    private TaskService taskService;
    @Mock
    private RepositoryReadService repositoryReadService;
    @Mock
    private GitHostClient gitHostClient;
    @Mock
    private GitHostClient giteaClient;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private PrAutoReviewService service;

    private AgentProject project;
    private PrAutoReviewConfig config;
    private AgentModelConfig defaultModel;
    private PrAutoReviewDelivery storedDelivery;
    private GiteaProperties giteaProperties;

    @BeforeAll
    static void initializeMapperColumns() {
        mybatis = new MybatisConfiguration();
        mybatis.setMapUnderscoreToCamelCase(true);
        mybatis.addMapper(AgentProjectMapper.class);
        mybatis.addMapper(PrAutoReviewDeliveryMapper.class);
        mybatis.addMapper(AgentReviewTaskMapper.class);
    }

    @BeforeEach
    void setUp() {
        giteaProperties = new GiteaProperties();
        service = new PrAutoReviewService(db, registry, objectMapper, giteaProperties, taskService, repositoryReadService);
        service.setEnableAsyncDispatch(false);

        when(db.mapper(AgentProjectMapper.class)).thenReturn(projectMapper);
        when(db.mapper(PrAutoReviewConfigMapper.class)).thenReturn(configMapper);
        when(db.mapper(PrAutoReviewDeliveryMapper.class)).thenReturn(deliveryMapper);
        when(db.mapper(AgentModelConfigMapper.class)).thenReturn(modelConfigMapper);
        when(db.mapper(AgentReviewTaskMapper.class)).thenReturn(reviewTaskMapper);
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
        when(deliveryMapper.selectOne(any())).thenAnswer(call -> {
            Object arg = call.getArgument(0);
            if (arg instanceof LambdaQueryWrapper<?> wrapper) {
                String sql = wrapper.getSqlSegment();
                if (sql != null && sql.contains("business_key")) {
                    return null;
                }
            }
            return storedDelivery;
        });
        when(reviewTaskMapper.selectOne(any())).thenReturn(null);
        when(deliveryMapper.selectById(any())).thenAnswer(call -> storedDelivery);
        when(deliveryMapper.update(any(), any())).thenReturn(1);

        defaultModel = new AgentModelConfig();
        defaultModel.setId("01MODEL00000000000000000001");
        defaultModel.setName("deepseek-chat");
        defaultModel.setIsDeleted(0);
        when(modelConfigMapper.selectById(defaultModel.getId())).thenReturn(defaultModel);

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
        config.setModelConfigId(defaultModel.getId());
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

    @AfterEach
    void shutdownService() {
        service.shutdown();
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

    // ================================================================= PRA-03 首次自动审查与评论回写

    @Test
    @DisplayName("PRA-03 GitHub 首次审查成功：创建审查任务并在原 PR 回写包含短 SHA 的普通评论")
    void githubFirstReviewCreatesTaskAndPostsComment() throws Exception {
        byte[] payload = loadFixtureBytes("fixtures/pra/github/pull_request_opened.json");
        String sig = "sha256=" + sign(SECRET, payload);
        PrAutoReviewDtos.WebhookReceiveResult receiveResult = service.receiveWebhook(
                "github", "github.com", "gh-del-first-1", "pull_request", null, sig, payload
        );
        assertEquals("RECEIVED", receiveResult.decisionState());

        PullRequestViews.Item pullItem = new PullRequestViews.Item(
                15, "feat: PR auto review initial integration", "open", false, false,
                "developer", "feature/auto-review", "main", "9da674bc34987d6acbec4f8521953315ecdb7d20",
                "f228b3da1c2ec0bf0cad8c6e3bb35b5bb7788b0b", "engineering/aiCodeReview",
                "engineering/aiCodeReview", true, "https://github.com/engineering/aiCodeReview/pull/15",
                "2026-11-12T03:00:00Z", "github"
        );
        when(repositoryReadService.pullRequest(eq(PROJECT_ID), eq(15L)))
                .thenReturn(new PullRequestViews.Detail(pullItem, "f228b3da1c2ec0bf0cad8c6e3bb35b5bb7788b0b", null));

        List<PullRequestViews.FileChange> changesList = List.of(
                new PullRequestViews.FileChange("src/App.vue", null, "MODIFIED", "TEXT", null),
                new PullRequestViews.FileChange("src/utils.ts", null, "ADDED", "TEXT", null)
        );
        PullRequestViews.Changes changes = new PullRequestViews.Changes(
                15, "9da674bc34987d6acbec4f8521953315ecdb7d20", "f228b3da1c2ec0bf0cad8c6e3bb35b5bb7788b0b",
                "f228b3da1c2ec0bf0cad8c6e3bb35b5bb7788b0b", "COMPLETE", null, 2, 2, 0, 0, changesList
        );
        when(repositoryReadService.pullRequestChanges(eq(PROJECT_ID), eq(15L))).thenReturn(changes);

        TaskDtos.View taskView = mockTaskView("01TASK00000000000000000001");
        ArgumentCaptor<TaskDtos.Create> createCaptor = ArgumentCaptor.forClass(TaskDtos.Create.class);
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        when(taskService.create(createCaptor.capture(), keyCaptor.capture(), eq("main"))).thenReturn(taskView);

        IssueComment postedComment = new IssueComment(
                8001L, 15L, new GitUserRef(901L, "code-review-bot", "Bot", null),
                "[查看提交 9da674b 的审查进度与结果](http://localhost:5173/#/reviews/01TASK00000000000000000001?from=/reviews)",
                Instant.now(), Instant.now()
        );
        ArgumentCaptor<String> commentBodyCaptor = ArgumentCaptor.forClass(String.class);
        when(gitHostClient.createIssueComment(any(), any(), any(), eq(15L), commentBodyCaptor.capture()))
                .thenReturn(postedComment);

        service.processDelivery(receiveResult.deliveryId());

        TaskDtos.Create created = createCaptor.getValue();
        assertEquals(PROJECT_ID, created.projectId());
        assertEquals("SPECIALIST", created.route());
        assertEquals("ISSUE", created.specialistType());
        assertEquals("PR", created.source().kind());
        assertEquals(15L, created.source().pullNumber());
        assertEquals("9da674bc34987d6acbec4f8521953315ecdb7d20", created.source().sourceHead());
        assertEquals("f228b3da1c2ec0bf0cad8c6e3bb35b5bb7788b0b", created.source().mergeBase());
        assertEquals(2, created.targets().size());
        assertEquals("PR #15 问题审查 · 9da674b", created.name());
        assertTrue(keyCaptor.getValue().startsWith("pr-auto-review:"));

        String commentBody = commentBodyCaptor.getValue();
        assertEquals("[查看提交 9da674b 的审查进度与结果](http://localhost:5173/#/reviews/01TASK00000000000000000001?from=/reviews)", commentBody);

        assertEquals("CREATED", storedDelivery.getDecisionState());
        assertEquals("01TASK00000000000000000001", storedDelivery.getReviewTaskId());
        assertEquals("SENT", storedDelivery.getCommentState());
        assertEquals(8001L, storedDelivery.getCommentId());
        assertEquals(201, storedDelivery.getCommentHttpStatus());
        assertNull(storedDelivery.getLeaseUntil());
    }

    @Test
    @DisplayName("PRA-03 Gitea 首次审查成功：通过 GiteaClient 在原 PR 回写普通评论")
    void giteaFirstReviewCreatesTaskAndPostsComment() throws Exception {
        project.setRepoUrl("http://192.168.1.50:3000/engineering/aiCodeReview.git");
        project.setCredential("gitea-token");
        project.setCredentialType(1);
        config.setRepoId(31L);
        config.setTargetBranch("main");

        byte[] payload = loadFixtureBytes("fixtures/pra/gitea/pull_request_opened.json");
        String sig = sign(SECRET, payload);
        PrAutoReviewDtos.WebhookReceiveResult receiveResult = service.receiveWebhook(
                "gitea", "http://192.168.1.50:3000", "gt-del-first-1", "pull_request", "pull_request", sig, payload
        );
        assertEquals("RECEIVED", receiveResult.decisionState());

        PullRequestViews.Item pullItem = new PullRequestViews.Item(
                13, "feat: gitea auto review integration", "open", false, false,
                "developer", "feature/auto-review", "main", "59abb71e9341934c8fc9183c23c4f3653f24d879",
                "f228b3da1c2ec0bf0cad8c6e3bb35b5bb7788b0b", "engineering/aiCodeReview",
                "engineering/aiCodeReview", true, "http://192.168.1.50:3000/engineering/aiCodeReview/pulls/13",
                "2026-11-12T03:00:00Z", "gitea"
        );
        when(repositoryReadService.pullRequest(eq(PROJECT_ID), eq(13L)))
                .thenReturn(new PullRequestViews.Detail(pullItem, "f228b3da1c2ec0bf0cad8c6e3bb35b5bb7788b0b", null));

        List<PullRequestViews.FileChange> changesList = List.of(
                new PullRequestViews.FileChange("backend/src/main/App.java", null, "MODIFIED", "TEXT", null)
        );
        PullRequestViews.Changes changes = new PullRequestViews.Changes(
                13, "59abb71e9341934c8fc9183c23c4f3653f24d879", "f228b3da1c2ec0bf0cad8c6e3bb35b5bb7788b0b",
                "f228b3da1c2ec0bf0cad8c6e3bb35b5bb7788b0b", "COMPLETE", null, 1, 1, 0, 0, changesList
        );
        when(repositoryReadService.pullRequestChanges(eq(PROJECT_ID), eq(13L))).thenReturn(changes);

        TaskDtos.View taskView = mockTaskView("01TASK00000000000000000002");
        when(taskService.create(any(), any(), eq("main"))).thenReturn(taskView);

        IssueComment postedComment = new IssueComment(
                8002L, 13L, new GitUserRef(901L, "code-review-bot", "Bot", null),
                "[查看提交 59abb71 的审查进度与结果](http://localhost:5173/#/reviews/01TASK00000000000000000002?from=/reviews)",
                Instant.now(), Instant.now()
        );
        ArgumentCaptor<String> commentCaptor = ArgumentCaptor.forClass(String.class);
        when(giteaClient.createIssueComment(any(), any(), any(), eq(13L), commentCaptor.capture()))
                .thenReturn(postedComment);

        service.processDelivery(receiveResult.deliveryId());

        verify(giteaClient).createIssueComment(any(), any(), any(), eq(13L), any());
        verify(gitHostClient, never()).createIssueComment(any(), any(), any(), any(Long.class), any());
        assertEquals("[查看提交 59abb71 的审查进度与结果](http://localhost:5173/#/reviews/01TASK00000000000000000002?from=/reviews)",
                commentCaptor.getValue());
        assertEquals("CREATED", storedDelivery.getDecisionState());
        assertEquals("SENT", storedDelivery.getCommentState());
        assertEquals(8002L, storedDelivery.getCommentId());
    }

    @Test
    @DisplayName("中断恢复场景：任务创建成功但写入审查 ID 前中断，恢复时找回原任务并不重复创建")
    void interruptedTaskCreationRecoversOriginalTaskAndPostsComment() throws Exception {
        byte[] payload = loadFixtureBytes("fixtures/pra/github/pull_request_opened.json");
        String sig = "sha256=" + sign(SECRET, payload);
        PrAutoReviewDtos.WebhookReceiveResult receiveResult = service.receiveWebhook(
                "github", "github.com", "gh-del-recov-1", "pull_request", null, sig, payload
        );

        String businessKey = service.calculateBusinessKey(storedDelivery);

        storedDelivery.setBusinessKey(businessKey);
        storedDelivery.setDecisionState("PROCESSING");
        storedDelivery.setLeaseUntil(AgentTime.now().minusSeconds(1));
        when(deliveryMapper.selectList(any())).thenReturn(List.of(storedDelivery));
        config.setEnabled(0);

        // 模拟已存在通过该业务键创建成功的原任务
        AgentReviewTask existingTask = new AgentReviewTask();
        existingTask.setId("01EXISTINGTASK000000000001");
        existingTask.setProjectId(PROJECT_ID);
        existingTask.setIdempotencyKey(businessKey);
        existingTask.setName("PR #15 问题审查 · 9da674b");
        existingTask.setInputSnapshot("""
                {"source":{"kind":"PR","sourceHead":"9da674bc34987d6acbec4f8521953315ecdb7d20","targetHead":"f228b3da1c2ec0bf0cad8c6e3bb35b5bb7788b0b","mergeBase":"f228b3da1c2ec0bf0cad8c6e3bb35b5bb7788b0b"},"coverage":{"textFiles":1,"binaryFiles":0,"unreadableFiles":0}}
                """);
        when(reviewTaskMapper.selectOne(any())).thenReturn(existingTask);

        IssueComment postedComment = new IssueComment(
                8003L, 15L, new GitUserRef(901L, "code-review-bot", "Bot", null),
                "review-link", Instant.now(), Instant.now()
        );
        when(gitHostClient.createIssueComment(any(), any(), any(), eq(15L), any())).thenReturn(postedComment);

        service.recoverExpiredLeases();
        service.processDelivery(receiveResult.deliveryId());

        verifyNoInteractions(taskService, repositoryReadService);
        assertEquals("CREATED", storedDelivery.getDecisionState());
        assertEquals("01EXISTINGTASK000000000001", storedDelivery.getReviewTaskId());
        assertEquals("SENT", storedDelivery.getCommentState());
        assertEquals(8003L, storedDelivery.getCommentId());
    }

    @Test
    @DisplayName("评论租约到期场景：SENDING 租约到期转为 UNKNOWN，核对找到原评论后转为 SENT")
    void interruptedCommentPostingWithExpiredLeaseRecoversViaReconciliation() throws Exception {
        PrAutoReviewDelivery d = new PrAutoReviewDelivery();
        d.setId("01DELIVERYRECOV0000000001");
        d.setProjectId(PROJECT_ID);
        d.setPrNumber(15L);
        d.setHostType("github");
        d.setHostInstanceKey("github.com");
        d.setDecisionState("CREATED");
        d.setReviewTaskId("01TASK00000000000000000001");
        d.setReviewUrl("http://localhost:5173/#/reviews/01TASK00000000000000000001?from=/reviews");
        d.setCommentState("SENDING");
        d.setCommentAttempts(1);
        d.setCommentActorId(901L);
        d.setCommentLastAttemptAt(AgentTime.now().minusMinutes(10));
        d.setLeaseUntil(AgentTime.now().minusMinutes(5));
        d.setRevision(1);

        when(deliveryMapper.selectList(any())).thenReturn(List.of(d));
        when(deliveryMapper.selectById(d.getId())).thenReturn(d);

        service.recoverExpiredLeases();

        assertEquals("UNKNOWN", d.getCommentState());
        assertNull(d.getLeaseUntil());
        assertNotNull(d.getNextAttemptAt());

        IssueComment foundComment = new IssueComment(
                9999L, 15L, new GitUserRef(901L, "code-review-bot", "Bot", null),
                "[查看提交 9da674b 的审查进度与结果](http://localhost:5173/#/reviews/01TASK00000000000000000001?from=/reviews)",
                Instant.now(), Instant.now()
        );
        IssueCommentPage page = new IssueCommentPage(List.of(foundComment), false, 1, 50);
        when(gitHostClient.listIssueComments(any(), any(), any(), eq(15L), anyInt(), anyInt())).thenReturn(page);

        service.reconcileComment(d);
        verify(gitHostClient, never()).listIssueComments(any(), any(), any(), any(Long.class), anyInt(), anyInt());
        d.setNextAttemptAt(AgentTime.now().minusSeconds(1));
        service.reconcileComment(d);

        assertEquals("SENT", d.getCommentState());
        assertEquals(9999L, d.getCommentId());
        assertNull(d.getNextAttemptAt());
    }

    @Test
    @DisplayName("评论超时场景：首次发送 504 转入 UNKNOWN，核对未查到后重试发送成功")
    void commentTimeoutEntersUnknownAndRetriesIfNotFoundOnRemote() throws Exception {
        PrAutoReviewDelivery d = new PrAutoReviewDelivery();
        d.setId("01DELIVERYTIMEOUT00000001");
        d.setProjectId(PROJECT_ID);
        d.setPrNumber(15L);
        d.setHostType("github");
        d.setHostInstanceKey("github.com");
        d.setDecisionState("CREATED");
        d.setReviewTaskId("01TASK00000000000000000001");
        d.setReviewUrl("http://localhost:5173/#/reviews/01TASK00000000000000000001?from=/reviews");
        d.setCommentBody("[查看提交 9da674b 的审查进度与结果](http://localhost:5173/#/reviews/01TASK00000000000000000001?from=/reviews)");
        d.setCommentState("PENDING");
        d.setCommentAttempts(0);
        d.setCommentActorId(901L);
        d.setRevision(1);

        when(gitHostClient.createIssueComment(any(), any(), any(), eq(15L), any()))
                .thenThrow(new HostHttpException(504, "504 Gateway Time-out", null, null));

        service.processComment(d);

        assertEquals("UNKNOWN", d.getCommentState());
        assertEquals(504, d.getCommentHttpStatus());
        assertEquals(1, d.getCommentAttempts());
        assertNotNull(d.getNextAttemptAt());

        // 模拟 60 秒后核对远端无此评论，应当转回 PENDING
        d.setCommentLastAttemptAt(AgentTime.now().minusSeconds(65));
        d.setNextAttemptAt(AgentTime.now().minusSeconds(1));
        IssueCommentPage emptyPage = new IssueCommentPage(List.of(), false, 1, 50);
        when(gitHostClient.listIssueComments(any(), any(), any(), eq(15L), anyInt(), anyInt())).thenReturn(emptyPage);

        service.reconcileComment(d);

        assertEquals("PENDING", d.getCommentState());
        assertNotNull(d.getNextAttemptAt());

        // 下一次重试发送成功
        IssueComment retriedComment = new IssueComment(
                8888L, 15L, new GitUserRef(901L, "code-review-bot", "Bot", null),
                d.getCommentBody(), Instant.now(), Instant.now()
        );
        when(gitHostClient.createIssueComment(any(), any(), any(), eq(15L), any())).thenReturn(retriedComment);

        service.processComment(d);
        assertEquals("PENDING", d.getCommentState());
        d.setNextAttemptAt(AgentTime.now().minusSeconds(1));
        service.processComment(d);

        assertEquals("SENT", d.getCommentState());
        assertEquals(8888L, d.getCommentId());
        assertEquals(2, d.getCommentAttempts());
    }

    @Test
    @DisplayName("重复触发场景：同一 PR 第二次投递因业务键冲突被跳过，关联原处理记录且业务键留空")
    void duplicateTriggerSkipsAndPointsToCanonicalRecord() throws Exception {
        byte[] payload = loadFixtureBytes("fixtures/pra/github/pull_request_opened.json");
        String sig = "sha256=" + sign(SECRET, payload);
        PrAutoReviewDtos.WebhookReceiveResult receiveResult = service.receiveWebhook(
                "github", "github.com", "gh-del-dup-trigger-2", "pull_request", null, sig, payload
        );
        String businessKey = service.calculateBusinessKey(storedDelivery);

        PullRequestViews.Item pullItem = new PullRequestViews.Item(
                15, "feat: PR auto review initial integration", "open", false, false,
                "developer", "feature/auto-review", "main", "9da674bc34987d6acbec4f8521953315ecdb7d20",
                "f228b3da1c2ec0bf0cad8c6e3bb35b5bb7788b0b", "engineering/aiCodeReview",
                "engineering/aiCodeReview", true, "https://github.com/engineering/aiCodeReview/pull/15",
                "2026-11-12T03:00:00Z", "github"
        );
        when(repositoryReadService.pullRequest(eq(PROJECT_ID), eq(15L)))
                .thenReturn(new PullRequestViews.Detail(pullItem, "f228b3da1c2ec0bf0cad8c6e3bb35b5bb7788b0b", null));

        List<PullRequestViews.FileChange> changesList = List.of(
                new PullRequestViews.FileChange("src/App.vue", null, "MODIFIED", "TEXT", null)
        );
        PullRequestViews.Changes changes = new PullRequestViews.Changes(
                15, "9da674bc34987d6acbec4f8521953315ecdb7d20", "f228b3da1c2ec0bf0cad8c6e3bb35b5bb7788b0b",
                "f228b3da1c2ec0bf0cad8c6e3bb35b5bb7788b0b", "COMPLETE", null, 1, 1, 0, 0, changesList
        );
        when(repositoryReadService.pullRequestChanges(eq(PROJECT_ID), eq(15L))).thenReturn(changes);

        // 模拟已存在一条持有该业务键的原投递记录
        PrAutoReviewDelivery primary = new PrAutoReviewDelivery();
        primary.setId("01PRIMARY00000000000000001");
        primary.setProjectId(PROJECT_ID);
        primary.setBusinessKey(businessKey);

        when(deliveryMapper.selectOne(any())).thenAnswer(call -> {
            LambdaQueryWrapper<?> wrapper = call.getArgument(0);
            if (wrapper.getSqlSegment() != null && wrapper.getSqlSegment().contains("business_key")) {
                return primary;
            }
            return storedDelivery;
        });

        service.processDelivery(receiveResult.deliveryId());

        assertEquals("SKIPPED", storedDelivery.getDecisionState());
        assertEquals("DUPLICATE_TRIGGER", storedDelivery.getDecisionCode());
        assertEquals(primary.getId(), storedDelivery.getCanonicalRecordId());
        assertNull(storedDelivery.getBusinessKey(), "重复触发记录 business_key 必须留空");
        verifyNoInteractions(taskService);
    }

    @Test
    @DisplayName("SOURCE_MOVED 重试场景：创建任务遇到提交变动，重试重新读取净变化后成功创建")
    void sourceMovedRetriesAndSucceedsOnNewHead() throws Exception {
        byte[] payload = loadFixtureBytes("fixtures/pra/github/pull_request_opened.json");
        String sig = "sha256=" + sign(SECRET, payload);
        PrAutoReviewDtos.WebhookReceiveResult receiveResult = service.receiveWebhook(
                "github", "github.com", "gh-del-moved-1", "pull_request", null, sig, payload
        );

        PullRequestViews.Item pullItem = new PullRequestViews.Item(
                15, "feat: PR auto review initial integration", "open", false, false,
                "developer", "feature/auto-review", "main", "9da674bc34987d6acbec4f8521953315ecdb7d20",
                "f228b3da1c2ec0bf0cad8c6e3bb35b5bb7788b0b", "engineering/aiCodeReview",
                "engineering/aiCodeReview", true, "https://github.com/engineering/aiCodeReview/pull/15",
                "2026-11-12T03:00:00Z", "github"
        );
        PullRequestViews.Item movedPullItem = new PullRequestViews.Item(
                15, "feat: PR auto review initial integration", "open", false, false,
                "developer", "feature/auto-review", "main", "8888888bc34987d6acbec4f8521953315ecdb7d20",
                "f228b3da1c2ec0bf0cad8c6e3bb35b5bb7788b0b", "engineering/aiCodeReview",
                "engineering/aiCodeReview", true, "https://github.com/engineering/aiCodeReview/pull/15",
                "2026-11-12T03:00:00Z", "github"
        );
        when(repositoryReadService.pullRequest(eq(PROJECT_ID), eq(15L)))
                .thenReturn(new PullRequestViews.Detail(pullItem, "f228b3da1c2ec0bf0cad8c6e3bb35b5bb7788b0b", null))
                .thenReturn(new PullRequestViews.Detail(movedPullItem, "f228b3da1c2ec0bf0cad8c6e3bb35b5bb7788b0b", null));

        List<PullRequestViews.FileChange> changesList1 = List.of(
                new PullRequestViews.FileChange("src/App.vue", null, "MODIFIED", "TEXT", null)
        );
        PullRequestViews.Changes changes1 = new PullRequestViews.Changes(
                15, "9da674bc34987d6acbec4f8521953315ecdb7d20", "f228b3da1c2ec0bf0cad8c6e3bb35b5bb7788b0b",
                "f228b3da1c2ec0bf0cad8c6e3bb35b5bb7788b0b", "COMPLETE", null, 1, 1, 0, 0, changesList1
        );
        List<PullRequestViews.FileChange> changesList2 = List.of(
                new PullRequestViews.FileChange("src/App.vue", null, "MODIFIED", "TEXT", null),
                new PullRequestViews.FileChange("src/new.ts", null, "ADDED", "TEXT", null)
        );
        PullRequestViews.Changes changes2 = new PullRequestViews.Changes(
                15, "8888888bc34987d6acbec4f8521953315ecdb7d20", "f228b3da1c2ec0bf0cad8c6e3bb35b5bb7788b0b",
                "f228b3da1c2ec0bf0cad8c6e3bb35b5bb7788b0b", "COMPLETE", null, 2, 2, 0, 0, changesList2
        );
        when(repositoryReadService.pullRequestChanges(eq(PROJECT_ID), eq(15L)))
                .thenReturn(changes1)
                .thenReturn(changes2);

        TaskDtos.View taskView = mockTaskView("01TASK00000000000000000003");
        when(taskService.create(any(), any(), eq("main")))
                .thenThrow(new ApiException(ErrorCode.SOURCE_MOVED, "源分支提交已变动"))
                .thenReturn(taskView);

        IssueComment postedComment = new IssueComment(
                8004L, 15L, new GitUserRef(901L, "code-review-bot", "Bot", null),
                "[查看提交 8888888 的审查进度与结果](http://localhost:5173/#/reviews/01TASK00000000000000000003?from=/reviews)",
                Instant.now(), Instant.now()
        );
        when(gitHostClient.createIssueComment(any(), any(), any(), eq(15L), any())).thenReturn(postedComment);

        service.processDelivery(receiveResult.deliveryId());

        assertEquals("CREATED", storedDelivery.getDecisionState());
        assertEquals("01TASK00000000000000000003", storedDelivery.getReviewTaskId());
        assertEquals("8888888bc34987d6acbec4f8521953315ecdb7d20", storedDelivery.getSourceHead());
        assertEquals(2, storedDelivery.getTextCount());
        assertEquals("SENT", storedDelivery.getCommentState());
    }

    @Test
    @DisplayName("审查阻断：没有可审查文本净变更时跳过，标记为 NO_TEXT_CHANGES")
    void noTextChangesSkipped() throws Exception {
        byte[] payload = loadFixtureBytes("fixtures/pra/github/pull_request_opened.json");
        String sig = "sha256=" + sign(SECRET, payload);
        PrAutoReviewDtos.WebhookReceiveResult receiveResult = service.receiveWebhook(
                "github", "github.com", "gh-del-notext", "pull_request", null, sig, payload
        );

        PullRequestViews.Item pullItem = new PullRequestViews.Item(
                15, "title", "open", false, false, "dev", "feat", "main", "sha1", "sha2",
                "engineering/aiCodeReview", "engineering/aiCodeReview", true, "url", "time", "github"
        );
        when(repositoryReadService.pullRequest(eq(PROJECT_ID), eq(15L)))
                .thenReturn(new PullRequestViews.Detail(pullItem, "baseSha", null));

        List<PullRequestViews.FileChange> changesList = List.of(
                new PullRequestViews.FileChange("img.png", null, "ADDED", "BINARY", null)
        );
        PullRequestViews.Changes changes = new PullRequestViews.Changes(
                15, "sha1", "sha2", "baseSha", "COMPLETE", null, 1, 0, 1, 0, changesList
        );
        when(repositoryReadService.pullRequestChanges(eq(PROJECT_ID), eq(15L))).thenReturn(changes);

        service.processDelivery(receiveResult.deliveryId());

        assertEquals("SKIPPED", storedDelivery.getDecisionState());
        assertEquals("NO_TEXT_CHANGES", storedDelivery.getDecisionCode());
        assertEquals(0, storedDelivery.getTextCount());
        assertEquals(1, storedDelivery.getBinaryCount());
        verifyNoInteractions(taskService);
    }

    @Test
    @DisplayName("审查阻断：文本目标超过 500 个上限时失败，标记为 VALIDATION_FAILED")
    void excessiveTextTargetsFailed() throws Exception {
        byte[] payload = loadFixtureBytes("fixtures/pra/github/pull_request_opened.json");
        String sig = "sha256=" + sign(SECRET, payload);
        PrAutoReviewDtos.WebhookReceiveResult receiveResult = service.receiveWebhook(
                "github", "github.com", "gh-del-excessive", "pull_request", null, sig, payload
        );

        PullRequestViews.Item pullItem = new PullRequestViews.Item(
                15, "title", "open", false, false, "dev", "feat", "main", "sha1", "sha2",
                "engineering/aiCodeReview", "engineering/aiCodeReview", true, "url", "time", "github"
        );
        when(repositoryReadService.pullRequest(eq(PROJECT_ID), eq(15L)))
                .thenReturn(new PullRequestViews.Detail(pullItem, "baseSha", null));

        List<PullRequestViews.FileChange> changesList = new java.util.ArrayList<>();
        for (int i = 0; i < 501; i++) {
            changesList.add(new PullRequestViews.FileChange("file" + i + ".ts", null, "ADDED", "TEXT", null));
        }
        PullRequestViews.Changes changes = new PullRequestViews.Changes(
                15, "sha1", "sha2", "baseSha", "COMPLETE", null, 501, 501, 0, 0, changesList
        );
        when(repositoryReadService.pullRequestChanges(eq(PROJECT_ID), eq(15L))).thenReturn(changes);

        service.processDelivery(receiveResult.deliveryId());

        assertEquals("FAILED", storedDelivery.getDecisionState());
        assertEquals("VALIDATION_FAILED", storedDelivery.getDecisionCode());
        assertEquals(501, storedDelivery.getTextCount());
        verifyNoInteractions(taskService);
    }

    @Test
    @DisplayName("评论上限保护：评论发送累计失败达到 3 次时，终态标记为 FAILED")
    void commentMaxAttemptsReachedFailed() {
        PrAutoReviewDelivery d = new PrAutoReviewDelivery();
        d.setId("01DELIVERYMAXATTEMPTS00000001");
        d.setProjectId(PROJECT_ID);
        d.setPrNumber(15L);
        d.setHostType("github");
        d.setHostInstanceKey("github.com");
        d.setDecisionState("CREATED");
        d.setReviewTaskId("01TASK00000000000000000001");
        d.setCommentBody("comment");
        d.setCommentState("PENDING");
        d.setCommentAttempts(2);
        d.setRevision(1);

        when(gitHostClient.createIssueComment(any(), any(), any(), eq(15L), any()))
                .thenThrow(new HostHttpException(500, "宿主返回 HTTP 500", null, null));

        service.processComment(d);

        assertEquals("FAILED", d.getCommentState());
        assertEquals(3, d.getCommentAttempts());
        assertEquals(500, d.getCommentHttpStatus());
        assertNull(d.getNextAttemptAt());
    }

    @Test
    void springCreatesTheServiceWithItsRequiredDependencies() {
        try (var context = new AnnotationConfigApplicationContext()) {
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context, "agent.datasource.url=jdbc:unused");
            context.registerBean(AgentDatabase.class, () -> db);
            context.registerBean(GitHostClientRegistry.class, () -> registry);
            context.registerBean(ObjectMapper.class, () -> objectMapper);
            context.registerBean(GiteaProperties.class, () -> giteaProperties);
            context.registerBean(TaskService.class, () -> taskService);
            context.registerBean(RepositoryReadService.class, () -> repositoryReadService);
            context.register(PrAutoReviewService.class);
            context.refresh();

            assertNotNull(context.getBean(PrAutoReviewService.class));
        }
    }

    @Test
    void stalePendingSnapshotsCanOnlySendOnceAndTransitionSqlClearsTheLease() {
        PrAutoReviewDelivery first = pendingComment();
        PrAutoReviewDelivery stale = pendingComment();
        AtomicInteger persistedRevision = new AtomicInteger(first.getRevision());
        when(deliveryMapper.update(any(), any())).thenAnswer(call -> {
            BoundSql sql = deliveryUpdateSql(call.getArgument(1));
            int nextRevision = (int) assignmentValue(sql, "revision");
            return persistedRevision.compareAndSet(nextRevision - 1, nextRevision) ? 1 : 0;
        });
        when(gitHostClient.createIssueComment(any(), any(), any(), eq(15L), any()))
                .thenReturn(issueComment(8001L, 901L, first.getCommentBody()));

        service.processComment(first);
        service.processComment(stale);

        assertEquals("SENT", first.getCommentState());
        assertEquals("PENDING", stale.getCommentState());
        verify(gitHostClient).createIssueComment(any(), any(), any(), eq(15L), any());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<PrAutoReviewDelivery>> updates =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(deliveryMapper, times(3)).update(eq(null), updates.capture());
        for (var update : updates.getAllValues()) {
            String sql = deliveryUpdateSql(update).getSql();
            assertTrue(sql.contains("id = ? AND revision = ?"));
            assertTrue(sql.contains("comment_state = ?"));
            assertFalse(sql.contains("receive_count"));
            assertFalse(sql.contains("last_received_at"));
        }
        BoundSql claim = deliveryUpdateSql(updates.getAllValues().get(0));
        assertTrue(claim.getSql().contains("next_attempt_at <= ?"));
        assertNotNull(assignmentValue(claim, "lease_until"));
        BoundSql sent = deliveryUpdateSql(updates.getAllValues().get(1));
        assertEquals("SENT", assignmentValue(sent, "comment_state"));
        assertNull(assignmentValue(sent, "lease_until"));
        assertNull(assignmentValue(sent, "next_attempt_at"));
        assertNull(assignmentValue(sent, "comment_error"));
        verify(deliveryMapper, never()).updateById(any(PrAutoReviewDelivery.class));
    }

    @Test
    void hostReadTimeoutAwaitsReconciliationWithoutAnotherPost() {
        PrAutoReviewDelivery delivery = pendingComment();
        when(gitHostClient.createIssueComment(any(), any(), any(), eq(15L), any())).thenThrow(
                HostHttpException.transport(new ResourceAccessException("response lost",
                        new SocketTimeoutException("Read timed out"))));

        service.processComment(delivery);
        service.reconcileComment(delivery);
        service.processComment(delivery);

        assertEquals("UNKNOWN", delivery.getCommentState());
        assertEquals(1, delivery.getCommentAttempts());
        assertNull(delivery.getCommentHttpStatus());
        assertNull(delivery.getLeaseUntil());
        assertTrue(delivery.getNextAttemptAt().isAfter(AgentTime.now()));
        verify(gitHostClient).createIssueComment(any(), any(), any(), eq(15L), any());
        verify(gitHostClient, never()).listIssueComments(any(), any(), any(), eq(15L), anyInt(), anyInt());
    }

    @Test
    void resolvesAndSavesTheCommentActorWhenOnlyALoginIsConfigured() {
        PrAutoReviewDelivery delivery = pendingComment();
        delivery.setCommentActorId(null);
        config.setBotActorId(null);
        when(gitHostClient.findUserByUsername(any(), any(), any(), eq("code-review-bot")))
                .thenReturn(new GitUserRef(901L, "code-review-bot", "Bot", null));
        when(gitHostClient.createIssueComment(any(), any(), any(), eq(15L), any())).thenAnswer(call -> {
            assertEquals(901L, delivery.getCommentActorId());
            throw new HostHttpException(504, "宿主返回 HTTP 504", null, null);
        });

        service.processComment(delivery);

        assertEquals("UNKNOWN", delivery.getCommentState());
        assertEquals(901L, delivery.getCommentActorId());
    }

    @Test
    void forbiddenCommentFailsOnTheFirstAttempt() {
        PrAutoReviewDelivery delivery = pendingComment();
        when(gitHostClient.createIssueComment(any(), any(), any(), eq(15L), any())).thenThrow(
                new HostHttpException(403, "宿主返回 HTTP 403", null, null));

        service.processComment(delivery);

        assertEquals("FAILED", delivery.getCommentState());
        assertEquals(403, delivery.getCommentHttpStatus());
        assertEquals(1, delivery.getCommentAttempts());
        assertNull(delivery.getNextAttemptAt());
    }

    @Test
    void reconciliationFindsTheOriginalBotCommentAfterPageTwenty() {
        PrAutoReviewDelivery delivery = commentToReconcile();
        config.setBotActorId(902L);
        when(gitHostClient.listIssueComments(any(), any(), any(), eq(15L), anyInt(), eq(50)))
                .thenAnswer(call -> {
                    int page = call.getArgument(4);
                    if (page < 21) {
                        IssueComment other = issueComment(page, 902L, delivery.getCommentBody());
                        return new IssueCommentPage(List.of(other), true, page, 50);
                    }
                    IssueComment posted = issueComment(1001L, 901L, delivery.getCommentBody());
                    return new IssueCommentPage(List.of(posted), false, page, 50);
                });

        service.reconcileComment(delivery);

        verify(gitHostClient).listIssueComments(any(), any(), any(), eq(15L), eq(21), eq(50));
        assertEquals("SENT", delivery.getCommentState());
        assertEquals(1001L, delivery.getCommentId());
        assertNull(delivery.getNextAttemptAt());
    }

    @Test
    void incompleteCommentPagesKeepTheOutcomeUnknown() {
        PrAutoReviewDelivery delivery = commentToReconcile();
        IssueComment other = issueComment(999L, 901L, "其他评论");
        when(gitHostClient.listIssueComments(any(), any(), any(), eq(15L), eq(1), eq(50)))
                .thenReturn(new IssueCommentPage(List.of(other), true, 1, 50));
        when(gitHostClient.listIssueComments(any(), any(), any(), eq(15L), eq(2), eq(50)))
                .thenReturn(new IssueCommentPage(List.of(), true, 2, 50));

        service.reconcileComment(delivery);
        service.processComment(delivery);

        assertEquals("UNKNOWN", delivery.getCommentState());
        assertNotNull(delivery.getNextAttemptAt());
        verify(gitHostClient, never()).createIssueComment(any(), any(), any(), eq(15L), any());
    }

    @Test
    void expiredTaskReplaysTheFrozenRequestAndBranchBeforeRereadingThePr() throws Exception {
        TaskDtos.Create frozen = frozenRequest();
        PrAutoReviewDelivery delivery = expiredTaskRequest(frozen);
        config.setTargetBranch("release");
        config.setModelConfigId(null);
        TaskDtos.View task = mockTaskView(TASK_ID);
        when(taskService.create(eq(frozen), eq(delivery.getBusinessKey()), eq("main"))).thenReturn(task);
        when(gitHostClient.createIssueComment(any(), any(), any(), eq(15L), any()))
                .thenReturn(issueComment(8001L, 901L, "回写"));

        service.recoverExpiredLeases();
        service.processDelivery(delivery.getId());

        assertEquals("CREATED", delivery.getDecisionState());
        assertEquals("SENT", delivery.getCommentState());
        assertEquals(frozen, objectMapper.readValue(delivery.getTaskRequestSnapshot(), TaskDtos.Create.class));
        verify(taskService).create(frozen, delivery.getBusinessKey(), "main");
        verifyNoInteractions(repositoryReadService);
    }

    @Test
    void createFailureAfterCommitRecoversTheOriginalTaskAndItsVersions() throws Exception {
        PrAutoReviewDelivery delivery = expiredTaskRequest(frozenRequest());
        AgentReviewTask original = new AgentReviewTask();
        original.setId(TASK_ID);
        original.setInputSnapshot("""
                {"source":{"sourceHead":"abcdef0123456789","targetBranch":"main"},"coverage":{"textFiles":2}}
                """);
        when(reviewTaskMapper.selectOne(any())).thenReturn(null, original);
        when(taskService.create(any(), any(), eq("main"))).thenThrow(new IllegalStateException("view failed"));
        when(gitHostClient.createIssueComment(any(), any(), any(), eq(15L), any()))
                .thenReturn(issueComment(8001L, 901L, "回写"));

        service.recoverExpiredLeases();
        service.processDelivery(delivery.getId());

        assertEquals("CREATED", delivery.getDecisionState());
        assertEquals("SENT", delivery.getCommentState());
        assertEquals(TASK_ID, delivery.getReviewTaskId());
        assertEquals("abcdef0123456789", delivery.getSourceHead());
        assertEquals(2, delivery.getTextCount());
        assertTrue(delivery.getCommentBody().contains("abcdef0"));
    }

    private PrAutoReviewDelivery pendingComment() {
        PrAutoReviewDelivery delivery = new PrAutoReviewDelivery();
        delivery.setId("01DELIVERYTIMEOUT00000001");
        delivery.setProjectId(PROJECT_ID);
        delivery.setPrNumber(15L);
        delivery.setHostType("github");
        delivery.setHostInstanceKey("github.com");
        delivery.setRepoId(31L);
        delivery.setTriggerKind("FIRST");
        delivery.setTargetBranch("main");
        delivery.setDecisionState("CREATED");
        delivery.setReviewTaskId(TASK_ID);
        delivery.setReviewUrl(service.formatReviewUrl(null, TASK_ID));
        delivery.setCommentBody(service.formatCommentBody(SOURCE_HEAD, delivery.getReviewUrl()));
        delivery.setCommentState("PENDING");
        delivery.setCommentAttempts(0);
        delivery.setCommentActorId(901L);
        delivery.setRevision(1);
        return delivery;
    }

    private PrAutoReviewDelivery commentToReconcile() {
        PrAutoReviewDelivery delivery = pendingComment();
        delivery.setCommentState("UNKNOWN");
        delivery.setCommentAttempts(1);
        delivery.setCommentLastAttemptAt(AgentTime.now().minusSeconds(90));
        delivery.setNextAttemptAt(AgentTime.now().minusSeconds(30));
        return delivery;
    }

    private TaskDtos.Create frozenRequest() {
        return new TaskDtos.Create(PROJECT_ID, "SPECIALIST", "ISSUE",
                new TaskDtos.Source("PR", null, null, null, null, 15L, SOURCE_HEAD, BASE_HEAD, BASE_HEAD),
                List.of(new TaskDtos.Target("src/App.vue", null, null)), defaultModel.getId(),
                null, null, false, "PR #15 问题审查 · 9da674b");
    }

    private PrAutoReviewDelivery expiredTaskRequest(TaskDtos.Create frozen) throws Exception {
        PrAutoReviewDelivery delivery = pendingComment();
        delivery.setDecisionState("PROCESSING");
        delivery.setCommentState("NONE");
        delivery.setReviewTaskId(null);
        delivery.setReviewUrl(null);
        delivery.setCommentActorId(null);
        delivery.setCommentBody(null);
        delivery.setBusinessKey(service.calculateBusinessKey(delivery));
        delivery.setTaskRequestSnapshot(objectMapper.writeValueAsString(frozen));
        delivery.setSourceHead(frozen.source().sourceHead());
        delivery.setTargetHead(frozen.source().targetHead());
        delivery.setMergeBase(frozen.source().mergeBase());
        delivery.setLeaseUntil(AgentTime.now().minusSeconds(1));
        delivery.setNextAttemptAt(delivery.getLeaseUntil());
        when(deliveryMapper.selectById(delivery.getId())).thenReturn(delivery);
        when(deliveryMapper.selectList(any())).thenReturn(List.of(delivery));
        return delivery;
    }

    private static IssueComment issueComment(long id, long actorId, String body) {
        return new IssueComment(id, 15L, new GitUserRef(actorId, "bot", "Bot", null),
                body, Instant.now(), Instant.now());
    }

    private static BoundSql deliveryUpdateSql(LambdaUpdateWrapper<PrAutoReviewDelivery> wrapper) {
        return mybatis.getMappedStatement(PrAutoReviewDeliveryMapper.class.getName() + ".update")
                .getBoundSql(Map.of("ew", wrapper));
    }

    private static Object assignmentValue(BoundSql statement, String column) {
        String sql = statement.getSql();
        String assignments = sql.substring(sql.indexOf("SET") + 3, sql.indexOf("WHERE")).strip();
        int index = Arrays.asList(assignments.split("\\s*,\\s*")).indexOf(column + "=?");
        assertTrue(index >= 0, () -> "UPDATE 缺少字段 " + column + "。SQL " + sql);
        String property = statement.getParameterMappings().get(index).getProperty();
        return mybatis.newMetaObject(statement.getParameterObject()).getValue(property);
    }

    private static TaskDtos.View mockTaskView(String id) {
        TaskDtos.View view = org.mockito.Mockito.mock(TaskDtos.View.class);
        when(view.id()).thenReturn(id);
        return view;
    }
}
