package com.codereview.agent.execution;

import com.codereview.agent.db.AgentDatabase;
import com.codereview.agent.entity.AgentIssue;
import com.codereview.agent.entity.AgentReviewResult;
import com.codereview.agent.entity.AgentReviewTask;
import com.codereview.agent.entity.AgentTaskTarget;
import com.codereview.agent.mapper.AgentIssueMapper;
import com.codereview.agent.mapper.AgentReviewResultMapper;
import com.codereview.agent.specialist.capacity.SpecialistResultMerger;
import com.codereview.agent.specialist.capacity.SpecialistUnitPlan;
import com.codereview.agent.specialist.material.JavaSourceStructure;
import com.codereview.agent.specialist.material.SpecialistMaterials;
import com.codereview.agent.specialist.protocol.SpecialistResultValidator;
import com.codereview.agent.specialist.protocol.SpecialistResults;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpecialistResultPublisherTest {

    private static final String SHA = "b7a312e0c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6";

    private final ObjectMapper mapper = new ObjectMapper();
    private AgentDatabase db;
    private AgentReviewResultMapper resultMapper;
    private AgentIssueMapper issueMapper;
    private SpecialistResultPublisher publisher;

    @BeforeEach
    void setUp() {
        db = mock(AgentDatabase.class);
        when(db.inTransaction(any())).thenAnswer(call -> ((java.util.function.Supplier<?>) call.getArgument(0)).get());
        resultMapper = mock(AgentReviewResultMapper.class);
        issueMapper = mock(AgentIssueMapper.class);
        when(db.mapper(AgentReviewResultMapper.class)).thenReturn(resultMapper);
        when(db.mapper(AgentIssueMapper.class)).thenReturn(issueMapper);
        publisher = new SpecialistResultPublisher(db, mapper, new SpecialistResultMerger());
    }

    @Test
    void publishesImmutableResultAndResolvedIssueLocations() throws Exception {
        AgentReviewTask task = task("ISSUE");
        AgentTaskTarget target = target();
        SpecialistMaterials.Material material = material("t1");
        SpecialistMaterials.PreparedTarget prepared = prepared(material);
        SpecialistResults.Issue issue = new SpecialistResults.Issue("CORRECTNESS", "HIGH",
                "重复扣款", List.of(new SpecialistResults.Location("t1", 2, 3)),
                "原因", "建议", List.of(), null);
        SpecialistResults.IssueResult result = new SpecialistResults.IssueResult("结论",
                List.of(issue), List.of("第三方不可取得"));
        SpecialistResultValidator.ValidatedResult validated = new SpecialistResultValidator.ValidatedResult(
                result, Map.of("t1", material), "1");
        SpecialistUnitPlan plan = new SpecialistUnitPlan(1, prepared,
                List.of(new JavaSourceStructure.LineRange(1, 10)), List.of());
        SpecialistUnitExecution execution = new SpecialistUnitExecution("u1", plan, validated, "c1");

        String resultId = publisher.publish(task, target, prepared, List.of(execution), true);

        ArgumentCaptor<AgentReviewResult> resultCaptor = ArgumentCaptor.forClass(AgentReviewResult.class);
        verify(resultMapper).insert(resultCaptor.capture());
        AgentReviewResult row = resultCaptor.getValue();
        assertEquals(resultId, row.getId());
        assertEquals("COMPLETE", row.getCompleteness());
        assertEquals("ISSUE", row.getKind());
        JsonNode provenance = mapper.readTree(row.getProvenance());
        assertEquals(SHA, provenance.path("versions").path("headSha").asText());
        assertEquals("t1", provenance.path("materials").get(0).path("id").asText());
        assertEquals("u1", provenance.path("units").get(0).path("id").asText());

        ArgumentCaptor<AgentIssue> issueCaptor = ArgumentCaptor.forClass(AgentIssue.class);
        verify(issueMapper).insert(issueCaptor.capture());
        AgentIssue issueRow = issueCaptor.getValue();
        JsonNode locations = mapper.readTree(issueRow.getLocations());
        assertEquals("src/A.java", locations.get(0).path("path").asText());
        assertEquals("FILE", locations.get(0).path("side").asText());
        assertTrue(issueRow.getSourceUnits().contains("u1"));
    }

    @Test
    void incompleteResultKeepsReadableContentButIsMarkedIncomplete() {
        AgentReviewTask task = task("PATTERN");
        AgentTaskTarget target = target();
        SpecialistMaterials.Material material = material("t1");
        SpecialistMaterials.PreparedTarget prepared = prepared(material);
        SpecialistResults.PatternResult result = new SpecialistResults.PatternResult("结论",
                List.of(), List.of("部分片段失败"));
        SpecialistResultValidator.ValidatedResult validated = new SpecialistResultValidator.ValidatedResult(
                result, Map.of("t1", material), "1");
        SpecialistUnitPlan plan = new SpecialistUnitPlan(1, prepared,
                List.of(new JavaSourceStructure.LineRange(1, 10)), List.of());

        publisher.publish(task, target, prepared,
                List.of(new SpecialistUnitExecution("u1", plan, validated, "c1")), false);

        ArgumentCaptor<AgentReviewResult> captor = ArgumentCaptor.forClass(AgentReviewResult.class);
        verify(resultMapper).insert(captor.capture());
        assertEquals("INCOMPLETE", captor.getValue().getCompleteness());
        verify(issueMapper, org.mockito.Mockito.never()).insert(any(AgentIssue.class));
    }

    @Test
    void issueSourcesExcludeUnrelatedUnitsAndKeepDuplicateSources() throws Exception {
        var material = material("t1");
        var prepared = prepared(material);
        var issue = new SpecialistResults.Issue("CORRECTNESS", "HIGH", "问题",
                List.of(new SpecialistResults.Location("t1", 2, 3)), "原因", "建议", List.of(), null);
        var withIssue = new SpecialistResultValidator.ValidatedResult(
                new SpecialistResults.IssueResult("有问题", List.of(issue), List.of()), Map.of("t1", material), "1");
        var empty = new SpecialistResultValidator.ValidatedResult(
                new SpecialistResults.IssueResult("无问题", List.of(), List.of()), Map.of("t1", material), "1");
        var plan = new SpecialistUnitPlan(1, prepared, List.of(), List.of("拆分诊断"));
        publisher.publish(task("ISSUE"), target(), prepared, List.of(
                new SpecialistUnitExecution("u1", plan, withIssue, "c1"),
                new SpecialistUnitExecution("u2", plan, empty, "c2"),
                new SpecialistUnitExecution("u3", plan, withIssue, "c3")), true);
        var issueCaptor = ArgumentCaptor.forClass(AgentIssue.class);
        verify(issueMapper).insert(issueCaptor.capture());
        assertEquals("[\"u1\",\"u3\"]", issueCaptor.getValue().getSourceUnits());
        var resultCaptor = ArgumentCaptor.forClass(AgentReviewResult.class);
        verify(resultMapper).insert(resultCaptor.capture());
        assertTrue(mapper.readTree(resultCaptor.getValue().getBody()).path("summary").asText().contains("无问题"));
        assertEquals("拆分诊断", mapper.readTree(resultCaptor.getValue().getProvenance())
                .path("units").get(0).path("notes").get(0).asText());
        verify(db).inTransaction(any());
    }

    @Test
    void couplingFactsAreFrozenWithThePublishedResult() throws Exception {
        var material = material("t1");
        var prepared = prepared(material);
        var facts = new com.codereview.agent.specialist.coupling.CouplingFacts(List.of(), 3, 2,
                Map.of("FIELD", 3), 0.6, SHA, "FILE", List.of("缺口"));
        var validated = new SpecialistResultValidator.ValidatedResult(
                new SpecialistResults.CouplingResult("结论", List.of(), List.of()), Map.of("t1", material), "1");
        publisher.publish(task("COUPLING"), target(), prepared, List.of(new SpecialistUnitExecution(
                "u1", new SpecialistUnitPlan(1, prepared, List.of(), List.of()), validated, "c1", facts)), true);
        var resultCaptor = ArgumentCaptor.forClass(AgentReviewResult.class);
        verify(resultMapper).insert(resultCaptor.capture());
        var stored = mapper.readTree(resultCaptor.getValue().getProvenance()).path("couplingFacts");
        assertEquals(3, stored.path("resolvedOutgoingCount").asInt());
        assertEquals(SHA, stored.path("sha").asText());
    }

    private static AgentReviewTask task(String type) {
        AgentReviewTask task = new AgentReviewTask();
        task.setId("01J9Z6Q3W4X5Y6Z7A8B9C0D1E2");
        task.setSpecialistType(type);
        return task;
    }

    private static AgentTaskTarget target() {
        AgentTaskTarget target = new AgentTaskTarget();
        target.setId("01J9Z6Q3W4X5Y6Z7A8B9C0D1E3");
        return target;
    }

    private static SpecialistMaterials.Material material(String id) {
        return new SpecialistMaterials.Material(id, "01J9Z6Q3W4X5Y6Z7A8B9C0D1E2", SHA,
                "src/A.java", SpecialistMaterials.Side.FILE, 1, 10, "code", "hash",
                "COMPLETE", null, SpecialistMaterials.Role.TARGET,
                SpecialistMaterials.Status.RESOLVED, null, null);
    }

    private static SpecialistMaterials.PreparedTarget prepared(SpecialistMaterials.Material material) {
        return new SpecialistMaterials.PreparedTarget("01J9Z6Q3W4X5Y6Z7A8B9C0D1E2", "ISSUE",
                "FILES", "main", "src/A.java", null, SHA, null, null, false, false,
                List.of(material), List.of(), List.of(), null, null);
    }
}
