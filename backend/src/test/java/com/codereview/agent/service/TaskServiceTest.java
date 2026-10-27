package com.codereview.agent.service;

import com.codereview.agent.api.ApiException;
import com.codereview.agent.api.ErrorCode;
import com.codereview.agent.db.AgentDatabase;
import com.codereview.agent.dto.TaskDtos;
import com.codereview.agent.entity.AgentIssue;
import com.codereview.agent.entity.AgentReviewResult;
import com.codereview.agent.entity.AgentModelCall;
import com.codereview.agent.entity.AgentModelConfig;
import com.codereview.agent.entity.AgentProject;
import com.codereview.agent.entity.AgentReviewTask;
import com.codereview.agent.entity.AgentTaskQuota;
import com.codereview.agent.entity.AgentTaskTarget;
import com.codereview.agent.execution.TaskDispatchService;
import com.codereview.agent.execution.TaskProperties;
import com.codereview.agent.execution.TaskStates;
import com.codereview.agent.invocation.ModelInvocationProperties;
import com.codereview.agent.mapper.AgentModelCallMapper;
import com.codereview.agent.mapper.AgentIssueMapper;
import com.codereview.agent.mapper.AgentReviewResultMapper;
import com.codereview.agent.mapper.AgentModelConfigMapper;
import com.codereview.agent.mapper.AgentProjectMapper;
import com.codereview.agent.mapper.AgentPromptMapper;
import com.codereview.agent.mapper.AgentPromptVersionMapper;
import com.codereview.agent.mapper.AgentReviewTaskMapper;
import com.codereview.agent.mapper.AgentTaskQuotaMapper;
import com.codereview.agent.mapper.AgentTaskTargetMapper;
import com.codereview.agent.repository.RepositoryReadService;
import com.codereview.agent.support.AgentTime;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DuplicateKeyException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TaskServiceTest {

    private static final String PROJECT_ID = "01J9Z6Q3W4X5Y6Z7A8B9C0D1F1";
    private static final String MODEL_ID = "01J9Z6Q3W4X5Y6Z7A8B9C0D1F2";
    private static final String TASK_ID = com.codereview.agent.support.AgentIds.next();
    private static final String CALL_ID = com.codereview.agent.support.AgentIds.next();
    private static final String HEAD = "b7a312e0c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6";
    private static final String OTHER = "12ac80f0a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6";

    @Mock
    private AgentDatabase db;
    @Mock
    private AgentProjectMapper projectMapper;
    @Mock
    private AgentModelConfigMapper modelMapper;
    @Mock
    private AgentPromptMapper promptMapper;
    @Mock
    private AgentPromptVersionMapper promptVersionMapper;
    @Mock
    private AgentReviewTaskMapper taskMapper;
    @Mock
    private AgentTaskTargetMapper targetMapper;
    @Mock
    private AgentTaskQuotaMapper quotaMapper;
    @Mock
    private AgentModelCallMapper callMapper;
    @Mock
    private AgentReviewResultMapper resultMapper;
    @Mock
    private AgentIssueMapper issueMapper;
    @Mock
    private RepositoryReadService repository;
    @Mock
    private TaskDispatchService dispatcher;

    private final ObjectMapper mapper = new ObjectMapper();
    private TaskService service;

    @BeforeEach
    void setUp() {
        for (Class<?> entity : List.of(AgentReviewTask.class, AgentTaskTarget.class,
                AgentTaskQuota.class, AgentModelCall.class, AgentReviewResult.class, AgentIssue.class)) {
            com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                    new org.apache.ibatis.builder.MapperBuilderAssistant(
                            new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""), entity);
        }
        service = new TaskService(db, mapper, repository, dispatcher,
                new ModelInvocationProperties(), new TaskProperties());
        when(db.mapper(AgentProjectMapper.class)).thenReturn(projectMapper);
        when(db.mapper(AgentModelConfigMapper.class)).thenReturn(modelMapper);
        when(db.mapper(AgentPromptMapper.class)).thenReturn(promptMapper);
        when(db.mapper(AgentPromptVersionMapper.class)).thenReturn(promptVersionMapper);
        when(db.mapper(AgentReviewTaskMapper.class)).thenReturn(taskMapper);
        when(db.mapper(AgentTaskTargetMapper.class)).thenReturn(targetMapper);
        when(db.mapper(AgentTaskQuotaMapper.class)).thenReturn(quotaMapper);
        when(db.mapper(AgentModelCallMapper.class)).thenReturn(callMapper);
        when(db.mapper(AgentReviewResultMapper.class)).thenReturn(resultMapper);
        when(db.mapper(AgentIssueMapper.class)).thenReturn(issueMapper);
        when(db.inTransaction(any())).thenAnswer(invocation ->
                ((Supplier<?>) invocation.getArgument(0)).get());
        when(projectMapper.selectById(any())).thenReturn(project());
        when(modelMapper.selectById(any())).thenReturn(model());
        when(taskMapper.insert(any(AgentReviewTask.class))).thenReturn(1);
        when(taskMapper.selectList(any())).thenReturn(List.of());
        when(targetMapper.selectList(any())).thenReturn(List.of(target()));
        when(quotaMapper.insert(any(AgentTaskQuota.class))).thenReturn(1);
        when(repository.resolveBranchHead(any(), any())).thenReturn(HEAD);
    }

    @Test
    void createsAQueuedTaskWithFixedVersionQuotaAndDispatch() {
        TaskDtos.Create req = request("key-1");

        TaskDtos.View view = service.create(req, "key-1");

        assertEquals(TaskStates.QUEUED, view.state());
        assertEquals(HEAD, view.headSha());
        assertEquals(1, view.targets().size());
        assertEquals("ISSUE", view.specialistType());
        assertNotNull(view.deadlineAt());
        ArgumentCaptor<AgentReviewTask> task = ArgumentCaptor.forClass(AgentReviewTask.class);
        verify(taskMapper).insert(task.capture());
        assertEquals(TaskStates.QUEUED, task.getValue().getState());
        assertEquals("key-1", task.getValue().getIdempotencyKey());
        assertFalse(task.getValue().getInputSnapshot().contains("credential"));
        assertTrue(task.getValue().getConfigSnapshot().contains("\"modelName\":\"deepseek-chat\""));
        assertFalse(task.getValue().getConfigSnapshot().contains("secret"));
        ArgumentCaptor<AgentTaskQuota> quota = ArgumentCaptor.forClass(AgentTaskQuota.class);
        verify(quotaMapper).insert(quota.capture());
        assertEquals(200, quota.getValue().getHardLimit());
        assertEquals(0, quota.getValue().getReserved());
        assertEquals("审查任务 " + task.getValue().getId(), task.getValue().getName());
        assertEquals(task.getValue().getName(), view.name());
        verify(dispatcher).dispatch(task.getValue().getId());
    }

    @Test
    void rejectsRetiredStrategyFieldInCreateRequest() {
        String json = "{\"projectId\":\"" + PROJECT_ID + "\",\"route\":\"SPECIALIST\","
                + "\"specialistType\":\"ISSUE\",\"source\":{\"kind\":\"FILES\"},"
                + "\"targets\":[{\"path\":\"A.java\"}],\"modelConfigId\":\"" + MODEL_ID
                + "\",\"strategyId\":\"retired\"}";

        var failure = assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,
                () -> mapper.readValue(json, TaskDtos.Create.class));

        assertTrue(failure.getMessage().contains("strategyId 已退出"));
    }

    @Test
    void createPersistsATrimmedReviewName() {
        TaskDtos.View view = service.create(request(null, "  支付重试  "), null);

        ArgumentCaptor<AgentReviewTask> task = ArgumentCaptor.forClass(AgentReviewTask.class);
        verify(taskMapper).insert(task.capture());
        assertEquals("支付重试", task.getValue().getName());
        assertEquals("支付重试", view.name());
    }

    @Test
    void createRejectsANameLongerThan200Characters() {
        ApiException error = assertThrows(ApiException.class,
                () -> service.create(request(null, "名".repeat(201)), null));

        assertEquals(ErrorCode.VALIDATION_FAILED, error.errorCode());
        verify(taskMapper, never()).insert(any(AgentReviewTask.class));
    }

    @Test
    void sourceMovedIsRejectedBeforeAnyTaskIsWritten() {
        when(repository.resolveBranchHead(any(), any())).thenReturn(OTHER);

        ApiException error = assertThrows(ApiException.class,
                () -> service.create(request(null), null));

        assertEquals(ErrorCode.SOURCE_MOVED, error.errorCode());
        verify(taskMapper, never()).insert(any(AgentReviewTask.class));
        verify(dispatcher, never()).dispatch(any());
    }

    @Test
    void sameIdempotencyKeyAndBodyReturnsTheOriginalTask() throws Exception {
        TaskDtos.Create req = request("key-2");
        AgentReviewTask existing = task("existing", hash(req));
        when(taskMapper.selectList(any())).thenReturn(List.of(existing));
        when(targetMapper.selectList(any())).thenReturn(List.of(target()));

        TaskDtos.View view = service.create(req, "key-2");

        assertEquals("existing", view.id());
        verify(taskMapper, never()).insert(any(AgentReviewTask.class));
    }

    @Test
    void sameIdempotencyKeyWithDifferentBodyConflicts() {
        AgentReviewTask existing = task("existing", "not-the-same-hash");
        when(taskMapper.selectList(any())).thenReturn(List.of(existing));

        ApiException error = assertThrows(ApiException.class,
                () -> service.create(request("key-3"), "key-3"));

        assertEquals(ErrorCode.IDEMPOTENCY_CONFLICT, error.errorCode());
    }

    @Test
    void concurrentDuplicateKeyReplaysTheExistingTask() throws Exception {
        TaskDtos.Create req = request("key-4");
        AgentReviewTask existing = task("existing", hash(req));
        when(taskMapper.selectList(any())).thenReturn(List.of(), List.of(existing));
        when(taskMapper.insert(any(AgentReviewTask.class)))
                .thenThrow(new DuplicateKeyException("duplicate"));

        TaskDtos.View view = service.create(req, "key-4");

        assertEquals("existing", view.id());
        verify(dispatcher, never()).dispatch(any());
    }

    @Test
    void cancelWritesTerminalStateAndClosesOpenTargets() {
        AgentReviewTask running = task(TASK_ID, null);
        running.setState(TaskStates.RUNNING);
        running.setRevision(0);
        AgentReviewTask cancelled = task(TASK_ID, null);
        cancelled.setState(TaskStates.CANCELLED);
        cancelled.setStopReason(TaskStates.STOP_USER_CANCELLED);
        cancelled.setCancelRequestedAt(AgentTime.now());
        cancelled.setRevision(1);
        when(taskMapper.selectById(any())).thenReturn(running, cancelled);
        when(taskMapper.update(any(), any())).thenReturn(1);
        when(targetMapper.update(any(), any())).thenReturn(1);

        TaskDtos.View view = service.cancel(TASK_ID, 0);

        assertEquals(TaskStates.CANCELLED, view.state());
        assertEquals(TaskStates.STOP_USER_CANCELLED, view.stopReason());
        assertEquals(1, view.revision());
        verify(targetMapper).update(any(), any());
    }

    @Test
    void cancelIsIdempotentForATerminalTask() {
        AgentReviewTask done = task(TASK_ID, null);
        done.setState(TaskStates.SUCCEEDED);
        when(taskMapper.selectById(any())).thenReturn(done);

        assertEquals(TaskStates.SUCCEEDED, service.cancel(TASK_ID, 0).state());
        verify(taskMapper, never()).update(any(), any());
    }

    @Test
    void copyInputReturnsNewDirectConfigAndRequiresReobservation() {
        AgentReviewTask task = task(TASK_ID, null);
        task.setInputSnapshot("{\"source\":{\"kind\":\"FILES\",\"branch\":\"main\",\"headSha\":\""
                + HEAD + "\"},\"targets\":[{\"path\":\"src/Order.java\",\"note\":\"关注并发\"}],"
                + "\"smartSplit\":false}");
        task.setConfigSnapshot("{\"schemaVersion\":2,\"model\":{\"id\":\"" + MODEL_ID
                + "\"},\"prompt\":{\"id\":\"prompt-1\",\"versionId\":\"version-1\"}}");
        when(taskMapper.selectById(any())).thenReturn(task);

        TaskDtos.CopyInput copy = service.copyInput(TASK_ID);

        assertTrue(copy.reobserveHead());
        assertEquals("main", copy.branch());
        assertEquals("src/Order.java", copy.targets().get(0).path());
        assertEquals(MODEL_ID, copy.modelConfigId());
        assertEquals("prompt-1", copy.promptId());
        assertEquals("version-1", copy.promptVersionId());
        assertEquals("审查任务 " + TASK_ID, copy.name());
    }

    @Test
    void copyInputRejectsTrialSnapshotWithoutTheNewConfigShape() {
        AgentReviewTask task = task(TASK_ID, null);
        task.setConfigSnapshot("{\"model\":{\"id\":\"" + MODEL_ID
                + "\"},\"strategy\":{\"id\":\"legacy-strategy\"}}");
        when(taskMapper.selectById(any())).thenReturn(task);

        ApiException error = assertThrows(ApiException.class, () -> service.copyInput(TASK_ID));

        assertEquals(ErrorCode.CONFIG_INCOMPLETE, error.errorCode());
        assertTrue(error.getMessage().contains("重新创建"));
    }

    @Test
    void callDetailReturnsTheSavedBytesAndHash() throws Exception {
        AgentModelCall call = new AgentModelCall();
        call.setId(CALL_ID);
        call.setRequestBody("{\"model\":\"deepseek-chat\"}");
        call.setResponseBody("{\"choices\":[]}");
        when(callMapper.selectById(any())).thenReturn(call);

        TaskDtos.CallDetail detail = service.callDetail(CALL_ID, "request");

        assertEquals("{\"model\":\"deepseek-chat\"}", detail.content());
        assertEquals(detail.content().getBytes(StandardCharsets.UTF_8).length, detail.length());
        assertEquals(sha256(detail.content()), detail.sha256());
        assertTrue(detail.complete());
    }

    @Test
    void detailNeverExposesTheModelCredential() {
        AgentReviewTask task = task(TASK_ID, null);
        task.setInputSnapshot("{\"source\":{\"kind\":\"FILES\",\"branch\":\"main\",\"headSha\":\""
                + HEAD + "\"},\"targets\":[],\"smartSplit\":false}");
        task.setConfigSnapshot("{\"model\":{\"id\":\"" + MODEL_ID + "\",\"name\":\"默认\","
                + "\"baseUrl\":\"https://gateway.local/v1\",\"modelName\":\"deepseek-chat\","
                + "\"protocol\":\"OPENAI_CHAT\",\"revision\":0}}");
        when(taskMapper.selectById(any())).thenReturn(task);

        TaskDtos.View view = service.detail(TASK_ID);

        assertFalse(view.toString().contains("secret"));
        assertEquals(MODEL_ID, view.config().modelConfigId());
    }

    @Test
    void detailIncludesEveryFilesIssueCountAndDistinguishesZeroFromUnpublished() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(task(TASK_ID, null));
        List<AgentTaskTarget> targets = java.util.stream.IntStream.rangeClosed(1, 4).mapToObj(index -> {
            AgentTaskTarget row = target();
            row.setId("target-" + index);
            row.setOrdinal(index);
            return row;
        }).toList();
        when(targetMapper.selectList(any())).thenReturn(targets);
        when(resultMapper.selectList(any())).thenReturn(List.of(
                publishedResult("r1", "target-1", "COMPLETE"),
                publishedResult("r2", "target-2", "COMPLETE"),
                publishedResult("r4", "target-4", "INCOMPLETE")));
        AgentIssue first = new AgentIssue(); first.setResultId("r1");
        AgentIssue second = new AgentIssue(); second.setResultId("r1");
        when(issueMapper.selectList(any())).thenReturn(List.of(first, second));

        var view = service.detail(TASK_ID);

        assertEquals(2, view.targets().get(0).issueCount());
        assertEquals(0, view.targets().get(1).issueCount());
        assertNull(view.targets().get(2).issueCount());
        assertNull(view.targets().get(3).issueCount());
        var resultQuery = ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);
        verify(resultMapper).selectList(resultQuery.capture());
        assertFalse(resultQuery.getValue().getSqlSelect().contains("body"));
        resultQuery.getValue().getSqlSegment();
        assertTrue(resultQuery.getValue().getParamNameValuePairs().containsValue(TASK_ID));
        var issueQuery = ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);
        verify(issueMapper).selectList(issueQuery.capture());
        issueQuery.getValue().getSqlSegment();
        assertTrue(issueQuery.getValue().getParamNameValuePairs().containsValue("r1"));
        assertTrue(issueQuery.getValue().getParamNameValuePairs().containsValue("r2"));
        assertFalse(issueQuery.getValue().getParamNameValuePairs().containsValue("r4"));
    }

    @Test
    void detailCountsOnlyTheLatestResultForEachTarget() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(task(TASK_ID, null));
        when(resultMapper.selectList(any())).thenReturn(List.of(
                publishedResult("latest", "target-1", "COMPLETE"),
                publishedResult("old", "target-1", "COMPLETE")));
        AgentIssue issue = new AgentIssue(); issue.setResultId("latest");
        when(issueMapper.selectList(any())).thenReturn(List.of(issue));

        assertEquals(1, service.detail(TASK_ID).targets().get(0).issueCount());

        var query = ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);
        verify(issueMapper).selectList(query.capture());
        query.getValue().getSqlSegment();
        assertFalse(query.getValue().getParamNameValuePairs().containsValue("old"));
    }

    @Test
    void nonIssueTasksDoNotReportZeroIssues() {
        AgentReviewTask task = task(TASK_ID, null);
        task.setSpecialistType("PATTERN");
        when(taskMapper.selectById(TASK_ID)).thenReturn(task);

        assertNull(service.detail(TASK_ID).targets().get(0).issueCount());
        verify(resultMapper, never()).selectList(any());
        verify(issueMapper, never()).selectList(any());
    }

    private static AgentReviewResult publishedResult(String id, String targetId, String completeness) {
        AgentReviewResult result = new AgentReviewResult();
        result.setId(id);
        result.setTargetId(targetId);
        result.setKind("ISSUE");
        result.setCompleteness(completeness);
        return result;
    }

    @Test
    void specialistUsesAndFreezesTheDirectlySelectedRuleVersion() throws Exception {
        var prompt = new com.codereview.agent.entity.AgentPrompt();
        prompt.setId(com.codereview.agent.support.AgentIds.next());
        prompt.setName("服务端通用规则");
        prompt.setPurpose("SPECIALIST");
        prompt.setCurrentVersionId(com.codereview.agent.support.AgentIds.next());
        var old = new com.codereview.agent.entity.AgentPromptVersion();
        old.setId(com.codereview.agent.support.AgentIds.next());
        old.setPromptId(prompt.getId());
        old.setVersionNo(1);
        old.setContent("旧版规则");
        var current = new com.codereview.agent.entity.AgentPromptVersion();
        current.setId(prompt.getCurrentVersionId());
        current.setPromptId(prompt.getId());
        current.setVersionNo(2);
        current.setContent("当前规则");
        when(promptMapper.selectById(prompt.getId())).thenReturn(prompt);
        when(promptVersionMapper.selectById(old.getId())).thenReturn(old);
        when(promptVersionMapper.selectById(current.getId())).thenReturn(current);
        var base = request(null);
        var req = new TaskDtos.Create(base.projectId(), base.route(), base.specialistType(), base.source(),
                base.targets(), MODEL_ID, prompt.getId(), old.getId(), base.smartSplit(), base.name());
        service.create(req, null);
        var currentReq = new TaskDtos.Create(base.projectId(), base.route(), base.specialistType(), base.source(),
                base.targets(), MODEL_ID, prompt.getId(), current.getId(), base.smartSplit(), base.name());
        service.create(currentReq, null);
        var tasks = ArgumentCaptor.forClass(AgentReviewTask.class);
        verify(taskMapper, org.mockito.Mockito.times(2)).insert(tasks.capture());
        var pinnedSnapshot = mapper.readTree(tasks.getAllValues().get(0).getConfigSnapshot()).path("prompt");
        assertEquals(old.getId(), pinnedSnapshot.path("versionId").asText());
        assertEquals("旧版规则", pinnedSnapshot.path("content").asText());
        assertEquals(2, mapper.readTree(tasks.getAllValues().get(0).getConfigSnapshot())
                .path("schemaVersion").asInt());
        assertEquals("当前规则", mapper.readTree(tasks.getAllValues().get(1).getConfigSnapshot())
                .path("prompt").path("content").asText());
        old.setContent("被覆盖");
        assertEquals("旧版规则", pinnedSnapshot.path("content").asText());
        assertThrows(ApiException.class, () -> service.create(new TaskDtos.Create(base.projectId(), base.route(),
                base.specialistType(), base.source(), base.targets(), MODEL_ID, prompt.getId(),
                com.codereview.agent.support.AgentIds.next(), base.smartSplit(), base.name()), null));
    }

    private TaskDtos.Create request(String unused) {
        return request(unused, null);
    }

    private TaskDtos.Create request(String unused, String name) {
        return new TaskDtos.Create(PROJECT_ID, "SPECIALIST", "ISSUE",
                new TaskDtos.Source("FILES", "main", HEAD, null, null),
                List.of(new TaskDtos.Target("src/Order.java", null, "关注并发")),
                MODEL_ID, null, null, false, name);
    }

    private AgentReviewTask task(String id, String hash) {
        AgentReviewTask task = new AgentReviewTask();
        task.setId(id);
        task.setProjectId(PROJECT_ID);
        task.setRoute("SPECIALIST");
        task.setSpecialistType("ISSUE");
        task.setState(TaskStates.QUEUED);
        task.setRevision(0);
        task.setRequestHash(hash);
        task.setCreatedAt(AgentTime.now());
        task.setUpdatedAt(AgentTime.now());
        task.setDeadlineAt(AgentTime.now().plusMinutes(30));
        task.setInputSnapshot("{\"source\":{\"kind\":\"FILES\",\"branch\":\"main\",\"headSha\":\""
                + HEAD + "\"},\"targets\":[{\"path\":\"src/Order.java\"}],\"smartSplit\":false}");
        task.setConfigSnapshot("{\"model\":{\"id\":\"" + MODEL_ID + "\",\"name\":\"默认\","
                + "\"modelName\":\"deepseek-chat\",\"revision\":0}}");
        return task;
    }

    private String hash(TaskDtos.Create req) throws Exception {
        return sha256(mapper.writeValueAsString(req));
    }

    private static AgentTaskTarget target() {
        AgentTaskTarget target = new AgentTaskTarget();
        target.setId("target-1");
        target.setTaskId("existing");
        target.setOrdinal(1);
        target.setPath("src/Order.java");
        target.setHeadSha(HEAD);
        target.setState(TaskStates.QUEUED);
        target.setRevision(0);
        target.setCreatedAt(AgentTime.now());
        target.setUpdatedAt(AgentTime.now());
        return target;
    }

    private static AgentProject project() {
        AgentProject project = new AgentProject();
        project.setId(PROJECT_ID);
        project.setName("订单");
        project.setRepoUrl("https://github.com/acme/order-service");
        project.setCurrentBranch("main");
        project.setRevision(1);
        return project;
    }

    private static AgentModelConfig model() {
        AgentModelConfig model = new AgentModelConfig();
        model.setId(MODEL_ID);
        model.setName("默认");
        model.setBaseUrl("https://gateway.local/v1");
        model.setModelName("deepseek-chat");
        model.setCredential("secret");
        model.setContextWindow(100_000);
        model.setMaxOutput(8_000);
        model.setProtocol("OPENAI_CHAT");
        model.setRevision(0);
        return model;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
