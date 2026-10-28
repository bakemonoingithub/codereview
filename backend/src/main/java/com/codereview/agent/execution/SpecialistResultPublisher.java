package com.codereview.agent.execution;

import com.codereview.agent.api.ApiException;
import com.codereview.agent.api.ErrorCode;
import com.codereview.agent.db.AgentDatabase;
import com.codereview.agent.entity.AgentIssue;
import com.codereview.agent.entity.AgentReviewResult;
import com.codereview.agent.entity.AgentReviewTask;
import com.codereview.agent.entity.AgentTaskTarget;
import com.codereview.agent.mapper.AgentIssueMapper;
import com.codereview.agent.mapper.AgentReviewResultMapper;
import com.codereview.agent.specialist.capacity.SpecialistResultMerger;
import com.codereview.agent.specialist.capacity.SpecialistUnitPlan;
import com.codereview.agent.specialist.material.SpecialistMaterials;
import com.codereview.agent.specialist.protocol.SpecialistProtocol;
import com.codereview.agent.specialist.protocol.SpecialistResultValidator;
import com.codereview.agent.specialist.protocol.SpecialistResults;
import com.codereview.agent.support.AgentIds;
import com.codereview.agent.service.ResultItemIndex;
import com.codereview.agent.support.AgentTime;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.codereview.agent.specialist.material.SpecialistMaterials.Material;

/**
 * 结果发布：合并片段、写入不可变结果与问题，并保存版本/材料/单元/调用来源。
 *
 * <p>完整文件所有单元成功时发布 COMPLETE；部分片段失败时保留已取得内容并发布 INCOMPLETE，
 * 该结果不进入报告与统计的正式选材。问题 ID 在发布时生成，之后只读。
 */
@Service
@ConditionalOnProperty(prefix = "agent.datasource", name = "url")
public class SpecialistResultPublisher {

    private final AgentDatabase db;
    private final ObjectMapper mapper;
    private final SpecialistResultMerger merger;

    public SpecialistResultPublisher(AgentDatabase db, ObjectMapper mapper,
                                     SpecialistResultMerger merger) {
        this.db = db;
        this.mapper = mapper;
        this.merger = merger;
    }

    public String publish(AgentReviewTask task, AgentTaskTarget target,
                          SpecialistMaterials.PreparedTarget prepared,
                          List<SpecialistUnitExecution> executions, boolean complete) {
        return db.inTransaction(() -> publishInTransaction(task, target, prepared, executions, complete));
    }

    private String publishInTransaction(AgentReviewTask task, AgentTaskTarget target,
                          SpecialistMaterials.PreparedTarget prepared,
                          List<SpecialistUnitExecution> executions, boolean complete) {
        if (executions == null || executions.isEmpty()) {
            throw new IllegalArgumentException("没有可发布的结果");
        }
        List<SpecialistResultValidator.ValidatedResult> validated = executions.stream()
                .map(SpecialistUnitExecution::validated).toList();
        SpecialistResults.Result merged = merger.merge(task.getSpecialistType(), validated);

        LocalDateTime now = AgentTime.now();
        AgentReviewResult result = new AgentReviewResult();
        result.setId(AgentIds.next());
        result.setTaskId(task.getId());
        result.setTargetId(target.getId());
        result.setKind(task.getSpecialistType());
        result.setSchemaVersion(SpecialistProtocol.VERSION);
        result.setBody(json(merged));
        result.setProvenance(json(provenance(prepared, executions)));
        result.setCompleteness(complete ? "COMPLETE" : "INCOMPLETE");
        result.setCreatedAt(now);
        db.mapper(AgentReviewResultMapper.class).insert(result);

        if (merged instanceof SpecialistResults.IssueResult issueResult) {
            publishIssues(result.getId(), issueResult, executions);
        }
        new ResultItemIndex(db, mapper).ensure(result);
        return result.getId();
    }

    private void publishIssues(String resultId, SpecialistResults.IssueResult result,
                               List<SpecialistUnitExecution> executions) {
        Map<String, Material> materials = new LinkedHashMap<>();
        for (SpecialistUnitExecution execution : executions) {
            materials.putAll(execution.validated().materials());
        }
        int ordinal = 1;
        for (SpecialistResults.Issue issue : result.issues()) {
            AgentIssue row = new AgentIssue();
            row.setId(AgentIds.next());
            row.setResultId(resultId);
            row.setOrdinal(ordinal++);
            row.setCategory(issue.category());
            row.setSeverity(issue.severity());
            row.setTitle(issue.title());
            row.setReason(issue.reason());
            row.setSuggestion(issue.suggestion());
            row.setLocations(json(locations(issue.locations(), materials)));
            String key = SpecialistResultMerger.issueKey(issue, materials);
            row.setSourceUnits(json(executions.stream().filter(execution ->
                    execution.validated().result() instanceof SpecialistResults.IssueResult part
                            && part.issues().stream().anyMatch(candidate -> key.equals(
                                    SpecialistResultMerger.issueKey(candidate, execution.validated().materials()))))
                    .map(SpecialistUnitExecution::unitId).distinct().toList()));
            db.mapper(AgentIssueMapper.class).insert(row);
        }
    }

    private List<Map<String, Object>> locations(List<SpecialistResults.Location> locations,
                                                Map<String, Material> materials) {
        List<Map<String, Object>> resolved = new ArrayList<>();
        for (SpecialistResults.Location location : locations) {
            Material material = materials.get(location.materialId());
            if (material == null) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("materialId", material.id());
            item.put("path", material.path());
            item.put("sha", material.sha());
            item.put("side", material.side().name());
            item.put("startLine", location.startLine());
            item.put("endLine", location.endLine());
            resolved.add(item);
        }
        return resolved;
    }

    private ObjectNode provenance(SpecialistMaterials.PreparedTarget prepared,
                                  List<SpecialistUnitExecution> executions) {
        ObjectNode root = mapper.createObjectNode();
        if (executions.get(0).couplingFacts() != null) {
            root.set("couplingFacts", mapper.valueToTree(executions.get(0).couplingFacts()));
        }
        ObjectNode versions = root.putObject("versions");
        versions.put("headSha", prepared.headSha());
        if (prepared.baseSha() != null) {
            versions.put("baseSha", prepared.baseSha());
        }
        versions.put("path", prepared.path());
        if (prepared.oldPath() != null) {
            versions.put("oldPath", prepared.oldPath());
        }
        ArrayNode gaps = root.putArray("materialGaps");
        for (SpecialistMaterials.Gap gap : prepared.gaps()) {
            gaps.addObject().put("code", gap.code()).put("subject", gap.subject())
                    .put("detail", gap.detail());
        }
        ArrayNode materials = root.putArray("materials");
        Set<String> seen = new LinkedHashSet<>();
        List<Material> all = new ArrayList<>(prepared.allMaterials());
        for (SpecialistUnitExecution execution : executions) {
            all.addAll(execution.plan().target().allMaterials());
        }
        for (Material material : all) {
            if (!seen.add(material.id())) {
                continue;
            }
            ObjectNode item = materials.addObject();
            item.put("id", material.id());
            item.put("path", material.path());
            item.put("sha", material.sha());
            item.put("side", material.side().name());
            item.put("startLine", material.startLine());
            item.put("endLine", material.endLine());
            item.put("role", material.role().name());
            item.put("completeness", material.completeness());
        }
        ArrayNode units = root.putArray("units");
        ArrayNode calls = root.putArray("calls");
        for (SpecialistUnitExecution execution : executions) {
            SpecialistUnitPlan plan = execution.plan();
            ObjectNode unit = units.addObject();
            unit.put("id", execution.unitId());
            unit.put("ordinal", plan.ordinal());
            unit.set("notes", mapper.valueToTree(plan.notes()));
            ArrayNode ranges = unit.putArray("ranges");
            for (var range : plan.ranges()) {
                ranges.addObject().put("startLine", range.startLine()).put("endLine", range.endLine());
            }
            calls.addObject().put("unitId", execution.unitId())
                    .put("callId", execution.callId());
        }
        return root;
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "结果序列化失败");
        }
    }
}
