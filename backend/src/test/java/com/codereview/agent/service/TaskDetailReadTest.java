package com.codereview.agent.service;

import com.codereview.agent.api.ApiException;
import com.codereview.agent.api.ErrorCode;
import com.codereview.agent.db.AgentDatabase;
import com.codereview.agent.dto.TaskDtos;
import com.codereview.agent.entity.AgentCallMaterial;
import com.codereview.agent.entity.AgentIssue;
import com.codereview.agent.entity.AgentMaterial;
import com.codereview.agent.entity.AgentModelCall;
import com.codereview.agent.entity.AgentReviewResult;
import com.codereview.agent.entity.AgentReviewTask;
import com.codereview.agent.entity.AgentTaskTarget;
import com.codereview.agent.execution.TaskDispatchService;
import com.codereview.agent.execution.TaskProperties;
import com.codereview.agent.invocation.ModelInvocationProperties;
import com.codereview.agent.mapper.AgentCallMaterialMapper;
import com.codereview.agent.mapper.AgentIssueMapper;
import com.codereview.agent.mapper.AgentMaterialMapper;
import com.codereview.agent.mapper.AgentModelCallMapper;
import com.codereview.agent.mapper.AgentReviewResultMapper;
import com.codereview.agent.mapper.AgentReviewTaskMapper;
import com.codereview.agent.mapper.AgentTaskTargetMapper;
import com.codereview.agent.repository.RepositoryReadService;
import com.codereview.agent.support.AgentIds;
import com.codereview.agent.support.AgentTime;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TaskDetailReadTest {

    private static final String TASK_ID = AgentIds.next();
    private static final String TARGET_ID = AgentIds.next();
    private static final String RESULT_ID = AgentIds.next();
    private static final String CALL_ID = AgentIds.next();
    private static final String MATERIAL_ID = AgentIds.next();

    private AgentDatabase db;
    private AgentReviewTaskMapper taskMapper;
    private AgentTaskTargetMapper targetMapper;
    private AgentReviewResultMapper resultMapper;
    private AgentIssueMapper issueMapper;
    private AgentModelCallMapper callMapper;
    private AgentCallMaterialMapper callMaterialMapper;
    private AgentMaterialMapper materialMapper;
    private TaskService service;

    @BeforeEach
    void setUp() {
        for (Class<?> entity : List.of(AgentReviewResult.class, AgentIssue.class,
                AgentModelCall.class, AgentCallMaterial.class, AgentMaterial.class)) {
            com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                    new org.apache.ibatis.builder.MapperBuilderAssistant(
                            new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""), entity);
        }
        db = mock(AgentDatabase.class);
        taskMapper = mock(AgentReviewTaskMapper.class);
        targetMapper = mock(AgentTaskTargetMapper.class);
        resultMapper = mock(AgentReviewResultMapper.class);
        issueMapper = mock(AgentIssueMapper.class);
        callMapper = mock(AgentModelCallMapper.class);
        callMaterialMapper = mock(AgentCallMaterialMapper.class);
        materialMapper = mock(AgentMaterialMapper.class);
        when(db.mapper(AgentReviewTaskMapper.class)).thenReturn(taskMapper);
        when(db.mapper(AgentTaskTargetMapper.class)).thenReturn(targetMapper);
        when(db.mapper(AgentReviewResultMapper.class)).thenReturn(resultMapper);
        when(db.mapper(AgentIssueMapper.class)).thenReturn(issueMapper);
        when(db.mapper(AgentModelCallMapper.class)).thenReturn(callMapper);
        when(db.mapper(AgentCallMaterialMapper.class)).thenReturn(callMaterialMapper);
        when(db.mapper(AgentMaterialMapper.class)).thenReturn(materialMapper);
        service = new TaskService(db, new ObjectMapper(), mock(RepositoryReadService.class),
                mock(TaskDispatchService.class), new ModelInvocationProperties(), new TaskProperties());
        when(taskMapper.selectById(TASK_ID)).thenReturn(task());
        when(targetMapper.selectById(TARGET_ID)).thenReturn(target());
    }

    @Test
    void returnsPublishedResultBodyAndIssuesWithoutRebuilding() {
        AgentReviewResult result = new AgentReviewResult();
        result.setId(RESULT_ID);
        result.setTaskId(TASK_ID);
        result.setTargetId(TARGET_ID);
        result.setKind("ISSUE");
        result.setSchemaVersion("1");
        result.setCompleteness("COMPLETE");
        result.setBody("{\"summary\":\"结论\",\"issues\":[],\"limitations\":[]}");
        result.setProvenance("{\"versions\":{\"headSha\":\"abc\"}}");
        result.setCreatedAt(AgentTime.now());
        when(resultMapper.selectList(any())).thenReturn(List.of(result));
        AgentIssue issue = new AgentIssue();
        issue.setId(AgentIds.next());
        issue.setResultId(RESULT_ID);
        issue.setOrdinal(1);
        issue.setCategory("CORRECTNESS");
        issue.setSeverity("HIGH");
        issue.setTitle("问题");
        issue.setLocations("[{\"path\":\"src/A.java\",\"startLine\":2}]");
        issue.setSourceUnits("[\"u1\"]");
        when(issueMapper.selectList(any())).thenReturn(List.of(issue));

        TaskDtos.ResultView view = service.result(TASK_ID, TARGET_ID);

        assertEquals("结论", view.body().path("summary").asText());
        assertEquals("COMPLETE", view.completeness());
        assertEquals("问题", view.issues().get(0).title());
        assertEquals("src/A.java", view.issues().get(0).locations().get(0).path("path").asText());
        assertEquals("abc", view.provenance().path("versions").path("headSha").asText());
    }

    @Test
    void listsActuallySentMaterialsAndTheirContent() {
        AgentModelCall call = call();
        when(callMapper.selectList(any())).thenReturn(List.of(call));
        AgentCallMaterial link = new AgentCallMaterial();
        link.setCallId(CALL_ID);
        link.setMaterialId(MATERIAL_ID);
        link.setRole("TARGET");
        link.setDisposition("SENT");
        when(callMaterialMapper.selectList(any())).thenReturn(List.of(link));
        AgentMaterial material = material();
        when(materialMapper.selectList(any())).thenReturn(List.of(material));
        when(materialMapper.selectById(MATERIAL_ID)).thenReturn(material);

        List<TaskDtos.MaterialView> materials = service.materials(TASK_ID, TARGET_ID);
        TaskDtos.MaterialDetail detail = service.material(TASK_ID, MATERIAL_ID);

        assertEquals(1, materials.size());
        assertEquals(MATERIAL_ID, materials.get(0).id());
        assertTrue(materials.get(0).sent());
        assertFalse(materials.get(0).contentHash().isBlank());
        assertEquals("code", detail.content());
        assertTrue(detail.complete());
    }

    @Test
    void materialFromAnotherTaskIsRejected() {
        when(callMapper.selectList(any())).thenReturn(List.of(call()));
        when(callMaterialMapper.selectList(any())).thenReturn(List.of());

        ApiException failure = assertThrows(ApiException.class,
                () -> service.material(TASK_ID, MATERIAL_ID));

        assertEquals(ErrorCode.NOT_FOUND, failure.errorCode());
    }

    @Test
    void callDetailFullIsScopedToTask() {
        when(callMapper.selectById(CALL_ID)).thenReturn(call());

        TaskDtos.CallDetailFull detail = service.callDetailFull(TASK_ID, CALL_ID);

        assertEquals("request-body", detail.requestBody());
        assertEquals("response-body", detail.responseBody());
        assertEquals(CALL_ID, detail.id());
    }

    private static AgentReviewTask task() {
        AgentReviewTask task = new AgentReviewTask();
        task.setId(TASK_ID);
        task.setProjectId(AgentIds.next());
        task.setSpecialistType("ISSUE");
        return task;
    }

    private static AgentTaskTarget target() {
        AgentTaskTarget target = new AgentTaskTarget();
        target.setId(TARGET_ID);
        target.setTaskId(TASK_ID);
        target.setPath("src/A.java");
        return target;
    }

    private static AgentModelCall call() {
        AgentModelCall call = new AgentModelCall();
        call.setId(CALL_ID);
        call.setTaskId(TASK_ID);
        call.setTargetId(TARGET_ID);
        call.setUnitId("u1");
        call.setAttempt(1);
        call.setPurpose("ISSUE_FILE");
        call.setState("SUCCEEDED");
        call.setRequestBody("request-body");
        call.setResponseBody("response-body");
        call.setCreatedAt(AgentTime.now());
        return call;
    }

    private static AgentMaterial material() {
        AgentMaterial material = new AgentMaterial();
        material.setId(MATERIAL_ID);
        material.setProjectId(AgentIds.next());
        material.setSha("a".repeat(40));
        material.setPath("src/A.java");
        material.setSide("FILE");
        material.setStartLine(1);
        material.setEndLine(4);
        material.setContent("code");
        material.setContentHash("hash");
        material.setCompleteness("COMPLETE");
        return material;
    }
}
