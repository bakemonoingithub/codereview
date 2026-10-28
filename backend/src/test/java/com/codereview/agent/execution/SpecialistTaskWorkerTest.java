package com.codereview.agent.execution;

import com.codereview.agent.db.AgentDatabase;
import com.codereview.agent.entity.AgentCallMaterial;
import com.codereview.agent.entity.AgentExecutionUnit;
import com.codereview.agent.entity.AgentModelCall;
import com.codereview.agent.entity.AgentModelConfig;
import com.codereview.agent.entity.AgentReviewTask;
import com.codereview.agent.entity.AgentTaskTarget;
import com.codereview.agent.invocation.CapacityEstimate;
import com.codereview.agent.invocation.InvocationResult;
import com.codereview.agent.invocation.ModelCapacityService;
import com.codereview.agent.invocation.ModelInvocationService;
import com.codereview.agent.mapper.AgentCallMaterialMapper;
import com.codereview.agent.mapper.AgentExecutionUnitMapper;
import com.codereview.agent.mapper.AgentModelCallMapper;
import com.codereview.agent.mapper.AgentModelConfigMapper;
import com.codereview.agent.mapper.AgentTaskTargetMapper;
import com.codereview.agent.prompt.PromptAssemblyService;
import com.codereview.agent.repository.RepositoryReadService;
import com.codereview.agent.specialist.capacity.SpecialistSplitPlanner;
import com.codereview.agent.specialist.capacity.SpecialistUnitPlan;
import com.codereview.agent.specialist.coupling.CouplingFactsBuilder;
import com.codereview.agent.specialist.material.DeclarationEnhancementService;
import com.codereview.agent.specialist.material.JavaSourceStructure;
import com.codereview.agent.specialist.material.JavaStructureParser;
import com.codereview.agent.specialist.material.MaterialPreparationService;
import com.codereview.agent.specialist.material.SpecialistMaterials;
import com.codereview.agent.specialist.protocol.SpecialistPromptBuilder;
import com.codereview.agent.specialist.protocol.SpecialistResultParser;
import com.codereview.agent.specialist.protocol.SpecialistResultValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpecialistTaskWorkerTest {

    private static final String TASK_ID = "01J9Z6Q3W4X5Y6Z7A8B9C0D1E2";
    private static final String TARGET_ID = "01J9Z6Q3W4X5Y6Z7A8B9C0D1E3";
    private static final String PROJECT_ID = "01J9Z6Q3W4X5Y6Z7A8B9C0D1E4";
    private static final String SHA = "b7a312e0c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6";

    private AgentDatabase db;
    private AgentTaskTargetMapper targetMapper;
    private AgentExecutionUnitMapper unitMapper;
    private AgentModelCallMapper callMapper;
    private AgentCallMaterialMapper callMaterialMapper;
    private MaterialPreparationService preparation;
    private DeclarationEnhancementService enhancement;
    private SpecialistSplitPlanner planner;
    private ModelInvocationService invocation;
    private SpecialistResultPublisher publisher;
    private SpecialistTaskWorker worker;
    private TaskExecutionGuard guard;
    private ModelCapacityService capacity;
    private AgentReviewTask task;
    private AgentTaskTarget target;
    private SpecialistMaterials.PreparedTarget prepared;

    @BeforeEach
    void setUp() {
        for (Class<?> entity : List.of(AgentTaskTarget.class, AgentExecutionUnit.class,
                AgentModelCall.class, AgentCallMaterial.class)) {
            com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                    new org.apache.ibatis.builder.MapperBuilderAssistant(
                            new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""), entity);
        }
        db = mock(AgentDatabase.class);
        when(db.inTransaction(any())).thenAnswer(call -> ((java.util.function.Supplier<?>) call.getArgument(0)).get());
        targetMapper = mock(AgentTaskTargetMapper.class);
        unitMapper = mock(AgentExecutionUnitMapper.class);
        callMapper = mock(AgentModelCallMapper.class);
        callMaterialMapper = mock(AgentCallMaterialMapper.class);
        AgentModelConfigMapper modelMapper = mock(AgentModelConfigMapper.class);
        when(db.mapper(AgentTaskTargetMapper.class)).thenReturn(targetMapper);
        when(db.mapper(AgentExecutionUnitMapper.class)).thenReturn(unitMapper);
        when(db.mapper(AgentModelCallMapper.class)).thenReturn(callMapper);
        when(db.mapper(AgentCallMaterialMapper.class)).thenReturn(callMaterialMapper);
        when(db.mapper(AgentModelConfigMapper.class)).thenReturn(modelMapper);
        when(targetMapper.selectList(any())).thenReturn(List.of(target()));
        when(targetMapper.update(any(), any())).thenReturn(1);
        when(unitMapper.insert(any(AgentExecutionUnit.class))).thenReturn(1);
        when(unitMapper.update(any(), any())).thenReturn(1);
        when(callMaterialMapper.insert(any(AgentCallMaterial.class))).thenReturn(1);

        AgentModelConfig model = new AgentModelConfig();
        model.setId("m1");
        model.setName("默认模型");
        model.setBaseUrl("https://gateway.local/v1");
        model.setModelName("model");
        model.setCredential("secret");
        model.setContextWindow(100_000);
        model.setMaxOutput(4096);
        model.setProtocol("OPENAI_CHAT");
        model.setRevision(1);
        when(modelMapper.selectById("m1")).thenReturn(model);

        AgentModelCall call = new AgentModelCall();
        call.setId("c1");
        call.setUnitId("unit");
        call.setAttempt(1);
        when(callMapper.selectList(any())).thenReturn(List.of(call));

        preparation = mock(MaterialPreparationService.class);
        enhancement = mock(DeclarationEnhancementService.class);
        planner = mock(SpecialistSplitPlanner.class);
        invocation = mock(ModelInvocationService.class);
        publisher = mock(SpecialistResultPublisher.class);
        capacity = mock(ModelCapacityService.class);
        when(capacity.estimate(any(), any())).thenReturn(new CapacityEstimate(5000, 100, 1024, 100_000, "ESTIMATED"));
        when(capacity.requireFits(any(), any())).thenReturn(new CapacityEstimate(1, 1, 1,
                100_000, "ESTIMATED"));
        guard = mock(TaskExecutionGuard.class);
        when(guard.requireActive(TASK_ID)).thenReturn(task());
        when(publisher.publish(any(), any(), any(), any(), eq(true))).thenReturn("r1");
        when(publisher.publish(any(), any(), any(), any(), eq(false))).thenReturn("r2");

        worker = new SpecialistTaskWorker(db, new ObjectMapper(), preparation, enhancement,
                mock(CouplingFactsBuilder.class), mock(RepositoryReadService.class),
                new JavaStructureParser(), planner, new PromptAssemblyService(),
                new SpecialistPromptBuilder(), new SpecialistResultParser(),
                new SpecialistResultValidator(), publisher, invocation, capacity, guard,
                new SpecialistExecutionProperties());

        task = task();
        target = target();
        prepared = prepared();
        when(preparation.prepare(any())).thenReturn(prepared);
        when(enhancement.enhance(prepared)).thenReturn(prepared);
        when(planner.plan(any(), any(), any(), any(), any(), eq(false))).thenReturn(List.of(unit()));
    }

    @Test
    void persistenceFailureDuringPublicationCannotMarkTargetOrTaskSuccessful() {
        when(invocation.invoke(any())).thenReturn(success("c1", validIssue()));
        when(publisher.publish(any(), any(), any(), any(), eq(true)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("item index write failed"));
        assertEquals(TaskStates.FAILED, worker.execute(task).state());
        var updates = org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class);
        verify(targetMapper, org.mockito.Mockito.atLeastOnce()).update(any(), updates.capture());
        var states = new java.util.ArrayList<Object>();
        for (var query : updates.getAllValues()) {
            var matcher = java.util.regex.Pattern.compile("state\\s*=\\s*#\\{ew\\.paramNameValuePairs\\.(\\w+)\\}")
                    .matcher(query.getSqlSet());
            if (matcher.find()) states.add(query.getParamNameValuePairs().get(matcher.group(1)));
        }
        org.junit.jupiter.api.Assertions.assertTrue(states.contains(TaskStates.FAILED));
        org.junit.jupiter.api.Assertions.assertFalse(states.contains(TaskStates.SUCCEEDED));
    }

    @Test
    void completeFilePublishesAndSucceedsTheTask() {
        when(invocation.invoke(any())).thenReturn(success("c1", validIssue()));

        TaskWorker.TaskOutcome outcome = worker.execute(task);

        assertEquals(TaskStates.SUCCEEDED, outcome.state());
        verify(publisher).publish(any(), any(), any(), any(), eq(true));
        verify(targetMapper, org.mockito.Mockito.atLeastOnce()).update(any(), any());
        verify(unitMapper).insert(any(AgentExecutionUnit.class));
    }

    @Test
    void formatFailureIsRetriedTwiceBeforeSuccess() {
        when(invocation.invoke(any()))
                .thenReturn(success("c1", "not json"))
                .thenReturn(success("c2", validIssue()));

        TaskWorker.TaskOutcome outcome = worker.execute(task);

        assertEquals(TaskStates.SUCCEEDED, outcome.state());
        verify(invocation, times(2)).invoke(any());
    }

    @Test
    void oneFailedFileDoesNotDiscardTheOtherFilesResult() {
        when(targetMapper.selectList(any())).thenReturn(List.of(
                target(TARGET_ID, "src/A.java"), target("t2", "src/B.java")));
        when(preparation.prepare(any())).thenAnswer(invocation -> {
            SpecialistMaterials.PrepareCommand command = invocation.getArgument(0);
            if (command.path().equals("src/B.java")) {
                return SpecialistMaterials.PreparedTarget.failure(command, "FILE_NOT_READABLE", "读不到");
            }
            return prepared;
        });
        when(invocation.invoke(any())).thenReturn(success("c1", validIssue()));

        TaskWorker.TaskOutcome outcome = worker.execute(task);

        assertEquals(TaskStates.PARTIAL, outcome.state());
        verify(publisher).publish(any(), any(), any(), any(), eq(true));
    }

    @Test
    void partialUnitsPublishIncompleteResultAndMarkTargetPartial() {
        when(planner.plan(any(), any(), any(), any(), any(), eq(false)))
                .thenReturn(List.of(unit(), unit()));
        when(invocation.invoke(any()))
                .thenThrow(new com.codereview.agent.api.ApiException(
                        com.codereview.agent.api.ErrorCode.MODEL_UNAVAILABLE, "网关失败"))
                .thenReturn(success("c2", validIssue()));

        TaskWorker.TaskOutcome outcome = worker.execute(task);

        assertEquals(TaskStates.PARTIAL, outcome.state());
        verify(publisher).publish(any(), any(), any(), any(), eq(false));
    }

    @Test
    void preparationFailureMarksTargetFailedWithoutPublishing() {
        when(preparation.prepare(any())).thenReturn(
                SpecialistMaterials.PreparedTarget.failure(new SpecialistMaterials.PrepareCommand(
                        PROJECT_ID, "ISSUE", "FILES", "main", "src/A.java", null, null,
                        SHA, null), "FILE_NOT_READABLE", "读不到"));

        TaskWorker.TaskOutcome outcome = worker.execute(task);

        assertEquals(TaskStates.FAILED, outcome.state());
        verify(publisher, org.mockito.Mockito.never()).publish(any(), any(), any(), any(), eq(true));
    }

    @Test
    void aLateResponseAfterCancellationCannotPublish() {
        when(invocation.invoke(any())).thenAnswer(call -> {
            when(guard.requireActive(TASK_ID)).thenThrow(new com.codereview.agent.api.ApiException(
                    com.codereview.agent.api.ErrorCode.TASK_CANCELLED, "已取消"));
            return success("late", validIssue());
        });
        org.junit.jupiter.api.Assertions.assertThrows(com.codereview.agent.api.ApiException.class,
                () -> worker.execute(task));
        verify(publisher, org.mockito.Mockito.never()).publish(any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void publicationChecksTheLockedTaskState() {
        when(invocation.invoke(any())).thenReturn(success("c1", validIssue()));
        when(guard.requireActiveLocked(TASK_ID)).thenThrow(new com.codereview.agent.api.ApiException(
                com.codereview.agent.api.ErrorCode.TASK_DEADLINE, "已到期限"));
        org.junit.jupiter.api.Assertions.assertThrows(com.codereview.agent.api.ApiException.class,
                () -> worker.execute(task));
        verify(publisher, org.mockito.Mockito.never()).publish(any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void linksEveryTransportAndFormatAttemptExactlyOnce() {
        AgentModelCall first = new AgentModelCall(); first.setId("c1");
        AgentModelCall second = new AgentModelCall(); second.setId("c2");
        AgentModelCall third = new AgentModelCall(); third.setId("c3");
        when(callMapper.selectList(any())).thenReturn(List.of(first, second, third));
        when(invocation.invoke(any())).thenReturn(success("c2", "not json"), success("c3", validIssue()));
        assertEquals(TaskStates.SUCCEEDED, worker.execute(task).state());
        var links = org.mockito.ArgumentCaptor.forClass(AgentCallMaterial.class);
        verify(callMaterialMapper, times(3)).insert(links.capture());
        assertEquals(List.of("c1", "c2", "c3"), links.getAllValues().stream().map(AgentCallMaterial::getCallId).toList());
        verify(callMapper).update(any(), any());
    }

    @Test
    void contextReplanningDiscardsPriorGenerationResults() {
        task.setInputSnapshot("{\"smartSplit\":true}");
        when(planner.plan(any(), any(), any(), any(), any(), eq(true))).thenReturn(
                List.of(unit(), unit()), List.of(unit()));
        when(invocation.invoke(any())).thenReturn(success("old", validIssue()))
                .thenThrow(new com.codereview.agent.api.ApiException(
                        com.codereview.agent.api.ErrorCode.CONTEXT_LIMIT, "模型容量超限"))
                .thenReturn(success("new", validIssue()));
        assertEquals(TaskStates.SUCCEEDED, worker.execute(task).state());
        var executions = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(publisher).publish(any(), any(), any(), executions.capture(), eq(true));
        assertEquals(1, executions.getValue().size());
        assertEquals("new", ((SpecialistUnitExecution) executions.getValue().get(0)).callId());
        var rows = org.mockito.ArgumentCaptor.forClass(AgentExecutionUnit.class);
        verify(unitMapper, times(3)).insert(rows.capture());
        assertEquals(List.of(1, 1, 2), rows.getAllValues().stream().map(AgentExecutionUnit::getGeneration).toList());
    }

    @Test
    void repeatedContextLimitsStopAfterTwoReplans() {
        task.setInputSnapshot("{\"smartSplit\":true}");
        when(planner.plan(any(), any(), any(), any(), any(), eq(true))).thenReturn(List.of(unit()));
        when(invocation.invoke(any())).thenThrow(new com.codereview.agent.api.ApiException(
                com.codereview.agent.api.ErrorCode.CONTEXT_LIMIT, "容量超限"));
        assertEquals(TaskStates.FAILED, worker.execute(task).state());
        verify(invocation, times(3)).invoke(any());
        verify(publisher, org.mockito.Mockito.never()).publish(any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void wholeFileCallMarksConservativeEstimateAsDiagnostic() {
        when(invocation.invoke(any())).thenReturn(success("c1", validIssue()));
        worker.execute(task);
        var request = org.mockito.ArgumentCaptor.forClass(com.codereview.agent.invocation.InvocationRequest.class);
        verify(invocation).invoke(request.capture());
        org.junit.jupiter.api.Assertions.assertTrue(request.getValue().sendWholeSpecialist());
    }

    @Test
    void explicitNullConfigurationRemainsFrozenInsteadOfReadingCurrentValues() {
        task.setConfigSnapshot("{\"model\":{\"id\":\"m1\",\"maxOutput\":null,\"tokenizer\":null}}");
        when(invocation.invoke(any())).thenReturn(success("c1", validIssue()));
        worker.execute(task);
        var request = org.mockito.ArgumentCaptor.forClass(com.codereview.agent.invocation.InvocationRequest.class);
        verify(invocation).invoke(request.capture());
        org.junit.jupiter.api.Assertions.assertNull(request.getValue().model().maxOutput());
        org.junit.jupiter.api.Assertions.assertNull(request.getValue().model().tokenizer());
    }

    @Test
    void invalidFormatExhaustsExactlyThreeAttempts() {
        when(invocation.invoke(any())).thenReturn(success("bad", "not json"));
        assertEquals(TaskStates.FAILED, worker.execute(task).state());
        verify(invocation, times(3)).invoke(any());
        verify(publisher, org.mockito.Mockito.never()).publish(any(), any(), any(), any(), anyBoolean());
    }

    private InvocationResult success(String callId, String content) {
        return new InvocationResult(callId, content, null, "stop", 1, 10, 10, "ESTIMATED",
                "openai-chat-v1");
    }

    private SpecialistUnitPlan unit() {
        return new SpecialistUnitPlan(1, prepared,
                List.of(new com.codereview.agent.specialist.material.JavaSourceStructure.LineRange(1, 4)),
                List.of());
    }

    private static String validIssue() {
        return """
                {"summary":"结论","issues":[{"category":"CORRECTNESS","severity":"HIGH",
                "title":"问题","locations":[{"materialId":"t1","startLine":2,"endLine":3}],
                "reason":"原因","suggestion":"建议","evidence":[],"changeRelation":null}],
                "limitations":[]}
                """;
    }

    private static SpecialistMaterials.PreparedTarget prepared() {
        SpecialistMaterials.Material material = new SpecialistMaterials.Material(
                "t1", PROJECT_ID, SHA, "src/A.java", SpecialistMaterials.Side.FILE, 1, 4,
                "class A {\n}\n", "hash", "COMPLETE", null, SpecialistMaterials.Role.TARGET,
                SpecialistMaterials.Status.RESOLVED, null,
                new JavaSourceStructure("src/A.java", "app", List.of(), List.of(),
                        List.of(), List.of(), 2, null));
        return new SpecialistMaterials.PreparedTarget(PROJECT_ID, "ISSUE", "FILES", "main",
                "src/A.java", null, SHA, null, null, false, false, List.of(material), List.of(),
                List.of(), null, null);
    }

    private static AgentReviewTask task() {
        AgentReviewTask task = new AgentReviewTask();
        task.setId(TASK_ID);
        task.setProjectId(PROJECT_ID);
        task.setSpecialistType("ISSUE");
        task.setInputSnapshot("{\"source\":{\"kind\":\"FILES\",\"branch\":\"main\"},"
                + "\"smartSplit\":false}");
        task.setConfigSnapshot("{\"schemaVersion\":2,\"model\":{\"id\":\"m1\"},"
                + "\"prompt\":{\"name\":\"通用规则\",\"content\":\"规则\"}}");
        task.setDeadlineAt(com.codereview.agent.support.AgentTime.now().plusMinutes(5));
        return task;
    }

    private static AgentTaskTarget target() {
        return target(TARGET_ID, "src/A.java");
    }

    private static AgentTaskTarget target(String id, String path) {
        AgentTaskTarget target = new AgentTaskTarget();
        target.setId(id);
        target.setTaskId(TASK_ID);
        target.setPath(path);
        target.setHeadSha(SHA);
        target.setState(TaskStates.QUEUED);
        return target;
    }
}
