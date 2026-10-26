package com.codereview.agent.service;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.codereview.agent.api.ApiException;
import com.codereview.agent.api.ErrorCode;
import com.codereview.agent.db.AgentDatabase;
import com.codereview.agent.dto.AgentTaskDtos;
import com.codereview.agent.entity.AgentModelConfig;
import com.codereview.agent.entity.AgentModelValidation;
import com.codereview.agent.entity.AgentProject;
import com.codereview.agent.entity.AgentPrompt;
import com.codereview.agent.entity.AgentPromptVersion;
import com.codereview.agent.entity.AgentReviewTask;
import com.codereview.agent.invocation.ModelInvocationProperties;
import com.codereview.agent.invocation.ModelSnapshot;
import com.codereview.agent.mapper.AgentModelConfigMapper;
import com.codereview.agent.mapper.AgentModelValidationMapper;
import com.codereview.agent.mapper.AgentProjectMapper;
import com.codereview.agent.mapper.AgentPromptMapper;
import com.codereview.agent.mapper.AgentPromptVersionMapper;
import com.codereview.agent.mapper.AgentReviewTaskMapper;
import com.codereview.agent.mapper.AgentTaskQuotaMapper;
import com.codereview.agent.repository.RepositoryReadService;
import com.codereview.agent.repository.RepositorySnapshots;
import com.codereview.agent.support.AgentTime;
import com.codereview.agent.tools.AgentToolLoopValidator;
import com.codereview.agent.execution.TaskStates;
import com.codereview.git.CommitDetail;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentTaskServiceTest {

    private static final String PROJECT_ID = "01J9Z6Q3W4X5Y6Z7A8B9C0D1E2";
    private static final String MODEL_ID = "01M23AKQZRG40082QFECKMC36N";
    private static final String HEAD = "b7a312e0c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6";
    private static final String OTHER = "c8b423f1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6a7";
    private static final String COMMIT_3 = "d9c53402e3f4a5b6c7d8e9f0a1b2c3d4e5f6a7b8";
    private static final String COMMIT_2 = "eaf64513f4a5b6c7d8e9f0a1b2c3d4e5f6a7b8c9";
    private static final String COMMIT_1 = "fba75624a5b6c7d8e9f0a1b2c3d4e5f6a7b8c9d0";

    @Mock private AgentDatabase db;
    @Mock private AgentProjectMapper projectMapper;
    @Mock private AgentModelConfigMapper modelMapper;
    @Mock private AgentModelValidationMapper validationMapper;
    @Mock private AgentPromptMapper promptMapper;
    @Mock private AgentPromptVersionMapper promptVersionMapper;
    @Mock private AgentReviewTaskMapper taskMapper;
    @Mock private AgentTaskQuotaMapper quotaMapper;
    @Mock private RepositoryReadService repository;

    private AgentTaskService service;
    private final ObjectMapper mapper = new ObjectMapper();
    private AgentProject project;
    private AgentModelConfig model;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new org.apache.ibatis.builder.MapperBuilderAssistant(
                new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""), AgentReviewTask.class);
        TableInfoHelper.initTableInfo(new org.apache.ibatis.builder.MapperBuilderAssistant(
                new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""), AgentModelValidation.class);
        service = new AgentTaskService(db, mapper, repository, new ModelInvocationProperties());
        project = new AgentProject();
        project.setId(PROJECT_ID);
        project.setName("订单服务");
        project.setRepoUrl("https://github.com/acme/order-service");
        project.setCurrentBranch("main");
        project.setRevision(3);
        model = new AgentModelConfig();
        model.setId(MODEL_ID);
        model.setName("DeepSeek");
        model.setBaseUrl("https://api.deepseek.com");
        model.setModelName("deepseek-flash");
        model.setProtocol("OPENAI_CHAT");
        model.setContextWindow(1_048_576);
        model.setMaxOutput(393_216);
        model.setRevision(2);
        model.setCredential("secret");

        when(db.mapper(AgentProjectMapper.class)).thenReturn(projectMapper);
        when(db.mapper(AgentModelConfigMapper.class)).thenReturn(modelMapper);
        when(db.mapper(AgentModelValidationMapper.class)).thenReturn(validationMapper);
        when(db.mapper(AgentPromptMapper.class)).thenReturn(promptMapper);
        when(db.mapper(AgentPromptVersionMapper.class)).thenReturn(promptVersionMapper);
        when(db.mapper(AgentReviewTaskMapper.class)).thenReturn(taskMapper);
        when(db.mapper(AgentTaskQuotaMapper.class)).thenReturn(quotaMapper);
        when(projectMapper.selectById(any())).thenReturn(project);
        when(modelMapper.selectById(any())).thenReturn(model);
        when(validationMapper.selectList(any())).thenReturn(List.of(validation()));
        when(taskMapper.selectList(any())).thenReturn(List.of());
        when(taskMapper.insert(any(AgentReviewTask.class))).thenReturn(1);
        when(quotaMapper.insert(any(com.codereview.agent.entity.AgentTaskQuota.class))).thenReturn(1);
        when(db.inTransaction(any())).thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(0)).get());
        when(repository.resolveBranchHead(PROJECT_ID, "main")).thenReturn(HEAD);
        when(repository.listPaths(eq(PROJECT_ID), any())).thenReturn(paths());
    }

    @Test
    void filesTaskFreezesVersionsProjectAndLimits() {
        AgentReviewTask task = createAndCapture(filesRequest());

        assertEquals("AGENT", task.getRoute());
        assertNull(task.getSpecialistType());
        assertEquals(TaskStates.QUEUED, task.getState());
        JsonNode input = read(task.getInputSnapshot());
        assertEquals(2, input.path("version").asInt());
        assertEquals("FILES", input.path("scope").path("kind").asText());
        assertEquals(HEAD, input.path("scope").path("versions").get(0).path("sha").asText());
        assertEquals("V1", input.path("scope").path("versions").get(0).path("version").asText());
        assertEquals("src/A.java", input.path("scope").path("paths").get(0).asText());
        assertEquals("检查重试链路", input.path("requirements").asText());
        assertEquals(HEAD, input.path("source").path("headSha").asText());
        assertEquals(AGENT_DEADLINE_MINUTES, java.time.Duration.between(
                task.getCreatedAt(), task.getDeadlineAt()).toMinutes());

        JsonNode config = read(task.getConfigSnapshot());
        assertEquals(3, config.path("version").asInt());
        assertEquals(MODEL_ID, config.path("model").path("id").asText());
        assertEquals(2, config.path("model").path("revision").asInt());
        assertEquals(com.codereview.agent.tools.AgentToolSchema.version(new com.codereview.agent.tools.ToolLimitProperties()), config.path("model").path("toolSchemaVersion").asText());
        assertEquals(48, config.path("limits").path("modelTurns").asInt());
        assertEquals(192, config.path("limits").path("toolAttempts").asInt());
        assertEquals(16384, config.path("limits").path("outputTokens").asInt());
        assertFalse(config.path("model").path("optionsHash").asText().isBlank());
        var quota = ArgumentCaptor.forClass(com.codereview.agent.entity.AgentTaskQuota.class);
        verify(quotaMapper).insert(quota.capture());
        assertEquals(config.path("limits").path("networkAttempts").asInt(), quota.getValue().getHardLimit());
    }

    @Test
    void movedHeadIsRejectedWithoutChangingTheObservedVersion() {
        when(repository.resolveBranchHead(PROJECT_ID, "main")).thenReturn(OTHER);

        ApiException error = assertThrows(ApiException.class,
                () -> service.create(filesRequest(), null));

        assertEquals(ErrorCode.SOURCE_MOVED, error.errorCode());
        assertTrue(error.getMessage().contains("分支已移动"));
    }

    @Test
    void selectedPathMustExistInTheFixedVersion() {
        AgentTaskDtos.Create req = new AgentTaskDtos.Create(PROJECT_ID, null, MODEL_ID, null,
                new AgentTaskDtos.Scope("FILES", "main", HEAD, List.of("src/Missing.java"),
                        List.of(), null),
                "检查重试链路", null);

        ApiException error = assertThrows(ApiException.class, () -> service.create(req, null));

        assertEquals(ErrorCode.VALIDATION_FAILED, error.errorCode());
        assertTrue(error.getMessage().contains("不存在"));
    }

    @Test
    void modelWithoutAgentValidationCannotStartATask() {
        when(validationMapper.selectList(any())).thenReturn(List.of());

        ApiException error = assertThrows(ApiException.class,
                () -> service.create(filesRequest(), null));

        assertEquals(ErrorCode.CONFIG_INCOMPLETE, error.errorCode());
        assertTrue(error.getMessage().contains("尚未通过"));
    }

    @Test
    void staleAgentValidationIsRejected() {
        AgentModelValidation stale = validation();
        stale.setConfigRevision(1);
        when(validationMapper.selectList(any())).thenReturn(List.of(stale));

        ApiException error = assertThrows(ApiException.class,
                () -> service.create(filesRequest(), null));

        assertEquals(ErrorCode.CONFIG_INCOMPLETE, error.errorCode());
    }

    @Test
    void commitsTaskKeepsOrderAndBuildsFirstParentComparisons() {
        when(repository.commit(PROJECT_ID, COMMIT_3))
                .thenReturn(commit(COMMIT_3, List.of(COMMIT_2)));
        when(repository.commit(PROJECT_ID, COMMIT_1))
                .thenReturn(commit(COMMIT_1, List.of()));
        AgentTaskDtos.Create req = new AgentTaskDtos.Create(PROJECT_ID, "两次提交", MODEL_ID, null,
                new AgentTaskDtos.Scope("COMMITS", null, null, List.of(),
                        List.of(COMMIT_3, COMMIT_1, COMMIT_3), "两次提交的重试改动"),
                "比较两次提交", null);

        AgentReviewTask task = createAndCapture(req);

        JsonNode scope = read(task.getInputSnapshot()).path("scope");
        assertEquals("COMMITS", scope.path("kind").asText());
        assertEquals(2, scope.path("commits").size());
        assertEquals(COMMIT_3, scope.path("commits").get(0).path("commitSha").asText());
        assertEquals(COMMIT_1, scope.path("commits").get(1).path("commitSha").asText());
        assertTrue(scope.path("commits").get(1).path("root").asBoolean());
        assertEquals("C1", scope.path("comparisons").get(0).path("id").asText());
        assertEquals(COMMIT_2, scope.path("comparisons").get(0).path("baseSha").asText());
        assertTrue(scope.path("comparisons").get(1).path("baseSha").isNull());
        // V1=head3, V2=base2, V3=head1
        assertEquals(3, scope.path("versions").size());
        assertEquals(COMMIT_3, scope.path("versions").get(0).path("sha").asText());
        assertEquals(COMMIT_2, scope.path("versions").get(1).path("sha").asText());
        assertEquals(COMMIT_1, scope.path("versions").get(2).path("sha").asText());
    }

    @Test
    void textScopeRequiresADescriptionAndRejectsFiles() {
        AgentTaskDtos.Create missing = new AgentTaskDtos.Create(PROJECT_ID, null, MODEL_ID, null,
                new AgentTaskDtos.Scope("TEXT", "main", HEAD, List.of(), List.of(), "  "),
                "调查支付", null);
        assertEquals(ErrorCode.VALIDATION_FAILED,
                assertThrows(ApiException.class, () -> service.create(missing, null)).errorCode());

        AgentTaskDtos.Create withFiles = new AgentTaskDtos.Create(PROJECT_ID, null, MODEL_ID, null,
                new AgentTaskDtos.Scope("TEXT", "main", HEAD, List.of("src/A.java"), List.of(),
                        "订单提交链路"),
                "调查支付", null);
        assertEquals(ErrorCode.VALIDATION_FAILED,
                assertThrows(ApiException.class, () -> service.create(withFiles, null)).errorCode());
    }

    @Test
    void guidanceMustUseTheAgentPurpose() {
        AgentPrompt prompt = new AgentPrompt();
        prompt.setId("01J9Z6Q3W4X5Y6Z7A8B9C0D1E4");
        prompt.setPurpose("SPECIALIST");
        prompt.setCurrentVersionId("01J9Z6Q3W4X5Y6Z7A8B9C0D1E5");
        when(promptMapper.selectById(any())).thenReturn(prompt);

        AgentTaskDtos.Create req = new AgentTaskDtos.Create(PROJECT_ID, null, MODEL_ID,
                prompt.getId(), new AgentTaskDtos.Scope("FILES", "main", HEAD,
                List.of("src/A.java"), List.of(), null), "检查重试链路", null);

        ApiException error = assertThrows(ApiException.class, () -> service.create(req, null));
        assertEquals(ErrorCode.VALIDATION_FAILED, error.errorCode());
        assertTrue(error.getMessage().contains("Agent 用途"));
    }

    @Test
    void guidanceVersionIsFrozenIntoTheConfigSnapshot() {
        AgentPrompt prompt = new AgentPrompt();
        prompt.setId("01J9Z6Q3W4X5Y6Z7A8B9C0D1E4");
        prompt.setName("调用方优先");
        prompt.setPurpose("AGENT");
        prompt.setCurrentVersionId("01J9Z6Q3W4X5Y6Z7A8B9C0D1E5");
        when(promptMapper.selectById(any())).thenReturn(prompt);
        AgentPromptVersion version = new AgentPromptVersion();
        version.setId(prompt.getCurrentVersionId());
        version.setPromptId(prompt.getId());
        version.setVersionNo(3);
        version.setContent("先看调用方。");
        version.setId("01J9Z6Q3W4X5Y6Z7A8B9C0D1E9");
        when(promptVersionMapper.selectById(version.getId())).thenReturn(version);

        AgentTaskDtos.Create req = new AgentTaskDtos.Create(PROJECT_ID, null, MODEL_ID,
                prompt.getId(), version.getId(), new AgentTaskDtos.Scope("FILES", "main", HEAD,
                List.of("src/A.java"), List.of(), null), "检查重试链路", null);
        AgentReviewTask task = createAndCapture(req);

        JsonNode config = read(task.getConfigSnapshot()).path("prompt");
        assertEquals(prompt.getId(), config.path("id").asText());
        assertEquals(3, config.path("versionNo").asInt());
        assertEquals("先看调用方。", config.path("content").asText());
        assertEquals(version.getId(), config.path("versionId").asText());
        when(taskMapper.selectById(task.getId())).thenReturn(task);
        assertEquals(version.getId(), service.copyInput(task.getId()).guidancePromptVersionId());
        version.setContent("覆盖后的正文");
        assertEquals("先看调用方。", read(task.getConfigSnapshot()).path("prompt").path("content").asText());
    }

    @Test
    void legacyIdempotencyHashStillReplaysWhenVersionIsOmitted() throws Exception {
        AgentTaskDtos.Create req = filesRequest();
        com.fasterxml.jackson.databind.node.ObjectNode legacy = mapper.valueToTree(req);
        legacy.remove("guidancePromptVersionId");
        String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(mapper.writeValueAsBytes(legacy)));
        AgentReviewTask existing = new AgentReviewTask();
        existing.setId("01J9Z6Q3W4X5Y6Z7A8B9C0D1E6");
        existing.setRoute("AGENT");
        existing.setState(TaskStates.QUEUED);
        existing.setRequestHash(hash);
        when(taskMapper.selectList(any())).thenReturn(List.of(existing));

        assertEquals(existing.getId(), service.create(req, "legacy-key").id());
        verify(taskMapper, org.mockito.Mockito.never()).insert(any(AgentReviewTask.class));
    }

    @Test
    void idempotencyKeyReplaysOrConflicts() {
        AgentReviewTask existing = new AgentReviewTask();
        existing.setId("01J9Z6Q3W4X5Y6Z7A8B9C0D1E6");
        existing.setRoute("AGENT");
        existing.setState(TaskStates.QUEUED);
        existing.setRequestHash("different");
        when(taskMapper.selectList(any())).thenReturn(List.of(existing));

        ApiException error = assertThrows(ApiException.class,
                () -> service.create(filesRequest(), "key-1"));

        assertEquals(ErrorCode.IDEMPOTENCY_CONFLICT, error.errorCode());
    }

    @Test
    void copyInputReturnsEditableScopeWithoutVersions() throws Exception {
        AgentReviewTask task = new AgentReviewTask();
        task.setId("01J9Z6Q3W4X5Y6Z7A8B9C0D1E7");
        task.setRoute("AGENT");
        task.setProjectId(PROJECT_ID);
        task.setName("Agent 任务");
        task.setInputSnapshot(mapper.writeValueAsString(Map.of(
                "version", 2,
                "scope", Map.of("kind", "FILES", "branch", "main", "observedSha", HEAD,
                        "paths", List.of("src/A.java"), "commits", List.of()),
                "requirements", "检查重试链路")));
        task.setConfigSnapshot(mapper.writeValueAsString(Map.of(
                "version", 2, "model", Map.of("id", MODEL_ID))));
        when(taskMapper.selectById(any())).thenReturn(task);

        AgentTaskDtos.CopyInput copy = service.copyInput(task.getId());

        assertEquals("FILES", copy.scope().kind());
        assertEquals(List.of("src/A.java"), copy.scope().paths());
        assertEquals("检查重试链路", copy.requirements());
        assertTrue(copy.reobserveHead());
        assertNull(copy.guidancePromptId());
    }

    @Test
    void guidanceRejectsOrphanMissingAndCrossPromptVersionsWithoutFallback() {
        AgentTaskDtos.Create base = filesRequest();
        String promptId = "01J9Z6Q3W4X5Y6Z7A8B9C0D1E4";
        String versionId = "01J9Z6Q3W4X5Y6Z7A8B9C0D1E5";
        AgentPrompt prompt = new AgentPrompt();
        prompt.setId(promptId);
        prompt.setPurpose("AGENT");
        prompt.setCurrentVersionId("01J9Z6Q3W4X5Y6Z7A8B9C0D1E9");
        when(promptMapper.selectById(promptId)).thenReturn(prompt);
        assertThrows(ApiException.class, () -> service.create(new AgentTaskDtos.Create(PROJECT_ID,
                null, MODEL_ID, null, versionId, base.scope(), base.requirements(), null), null));
        AgentTaskDtos.Create explicit = new AgentTaskDtos.Create(PROJECT_ID, null, MODEL_ID,
                promptId, versionId, base.scope(), base.requirements(), null);
        assertThrows(ApiException.class, () -> service.create(explicit, null));
        AgentPromptVersion wrong = new AgentPromptVersion();
        wrong.setId(versionId);
        wrong.setPromptId("01J9Z6Q3W4X5Y6Z7A8B9C0D1E8");
        wrong.setContent("另一个提示词");
        when(promptVersionMapper.selectById(versionId)).thenReturn(wrong);
        assertThrows(ApiException.class, () -> service.create(explicit, null));
        verify(promptVersionMapper, org.mockito.Mockito.never()).selectById(prompt.getCurrentVersionId());
    }

    private AgentReviewTask createAndCapture(AgentTaskDtos.Create req) {
        service.create(req, null);
        ArgumentCaptor<AgentReviewTask> captor = ArgumentCaptor.forClass(AgentReviewTask.class);
        verify(taskMapper).insert(captor.capture());
        return captor.getValue();
    }

    private AgentTaskDtos.Create filesRequest() {
        return new AgentTaskDtos.Create(PROJECT_ID, "重试链路", MODEL_ID, null,
                new AgentTaskDtos.Scope("FILES", "main", HEAD, List.of("src/A.java"),
                        List.of(), "订单提交和支付重试链路"),
                "检查重试链路", null);
    }

    private AgentModelValidation validation() {
        AgentModelValidation validation = new AgentModelValidation();
        validation.setId("01J9Z6Q3W4X5Y6Z7A8B9C0D1E8");
        validation.setModelConfigId(MODEL_ID);
        validation.setConfigRevision(2);
        validation.setScope(AgentToolLoopValidator.SCOPE);
        validation.setResult("PASSED");
        validation.setCreatedAt(AgentTime.now());
        validation.setDetailJson("{\"optionsHash\":\""
                + AgentToolLoopValidator.optionsHash(ModelSnapshot.from(model)) + "\"}");
        return validation;
    }

    private static CommitDetail commit(String sha, List<String> parents) {
        return new CommitDetail(sha, parents, "msg", "author", "today", 1, 1, 2, false, List.of());
    }

    private static RepositorySnapshots.PathList paths() {
        return new RepositorySnapshots.PathList(List.of(
                new RepositorySnapshots.PathEntry("src", "tree", false),
                new RepositorySnapshots.PathEntry("src/A.java", "blob", true)), HEAD, false);
    }

    private JsonNode read(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static final int AGENT_DEADLINE_MINUTES = 20;
}
