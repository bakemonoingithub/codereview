package com.codereview.agent.execution;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.codereview.agent.api.ApiException;
import com.codereview.agent.api.ErrorCode;
import com.codereview.agent.db.AgentDatabase;
import com.codereview.agent.entity.AgentReviewResult;
import com.codereview.agent.entity.AgentReviewTask;
import com.codereview.agent.mapper.AgentReviewResultMapper;
import com.codereview.agent.mapper.AgentReviewTaskMapper;
import com.codereview.agent.support.AgentIds;
import com.codereview.agent.support.AgentTime;
import com.codereview.agent.tools.AgentSubmission;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 结果的唯一发布入口。
 *
 * <p>结果写入与任务终态在同一个新库事务里：先锁定任务行，确认没有取消、没有已发布结果，
 * 再写 {@code review_result(kind=AGENT, schema_version=1)} 并把任务条件更新为
 * {@code SUCCEEDED}。任一步失败整体回滚，不会留下"可选材但没有终态"的结果。
 */
@Service
@ConditionalOnProperty(prefix = "agent.datasource", name = "url")
public class AgentResultPublisher {

    private final AgentDatabase db;
    private final ObjectMapper mapper;

    public AgentResultPublisher(AgentDatabase db, ObjectMapper mapper) {
        this.db = db;
        this.mapper = mapper;
    }

    public String publish(String taskId, AgentSubmission submission,
                          List<AgentSubmission.SentMaterial> materials,
                          List<Map<String, Object>> comparisons, int toolCalls) {
        String resultId = AgentIds.next();
        String body = body(submission, materials);
        String provenance = provenance(materials, comparisons, toolCalls);
        String stopReason = submission.answered() ? null : TaskStates.STOP_INSUFFICIENT_EVIDENCE;

        db.inTransaction(() -> {
            AgentReviewTask task = db.mapper(AgentReviewTaskMapper.class).selectOne(
                    new LambdaQueryWrapper<AgentReviewTask>()
                            .eq(AgentReviewTask::getId, taskId)
                            .last("FOR UPDATE"));
            if (task == null) {
                throw new ApiException(ErrorCode.NOT_FOUND, "任务不存在：" + taskId);
            }
            if (task.getCancelRequestedAt() != null || TaskStates.isTerminal(task.getState())) {
                throw new ApiException(ErrorCode.TASK_CANCELLED, "任务已取消或结束，不能发布结果");
            }
            Long existing = db.mapper(AgentReviewResultMapper.class).selectCount(
                    new LambdaQueryWrapper<AgentReviewResult>()
                            .eq(AgentReviewResult::getTaskId, taskId)
                            .eq(AgentReviewResult::getKind, "AGENT"));
            if (existing != null && existing > 0) {
                throw new ApiException(ErrorCode.CONFLICT, "该任务已发布 Agent 结果");
            }
            LocalDateTime now = AgentTime.now();
            AgentReviewResult result = new AgentReviewResult();
            result.setId(resultId);
            result.setTaskId(taskId);
            result.setTargetId(null);
            result.setKind("AGENT");
            result.setSchemaVersion("1");
            result.setBody(body);
            result.setProvenance(provenance);
            result.setCompleteness("COMPLETE");
            result.setCreatedAt(now);
            db.mapper(AgentReviewResultMapper.class).insert(result);

            int updated = db.mapper(AgentReviewTaskMapper.class).update(null,
                    new LambdaUpdateWrapper<AgentReviewTask>()
                            .eq(AgentReviewTask::getId, taskId)
                            .eq(AgentReviewTask::getState, TaskStates.RUNNING)
                            .isNull(AgentReviewTask::getCancelRequestedAt)
                            .set(AgentReviewTask::getState, TaskStates.SUCCEEDED)
                            .set(AgentReviewTask::getStopReason, stopReason)
                            .set(AgentReviewTask::getFinishedAt, now)
                            .set(AgentReviewTask::getUpdatedAt, now)
                            .setSql("revision = revision + 1"));
            if (updated == 0) {
                throw new ApiException(ErrorCode.TASK_CANCELLED, "任务已被取消，结果不发布");
            }
            return null;
        });
        return resultId;
    }

    private String body(AgentSubmission submission, List<AgentSubmission.SentMaterial> materials) {
        Map<String, AgentSubmission.SentMaterial> byId = new LinkedHashMap<>();
        materials.forEach(material -> byId.put(material.materialId(), material));
        ObjectNode root = mapper.createObjectNode();
        root.put("title", submission.title());
        root.put("summary", submission.summary());
        root.put("answerStatus", submission.answerStatus());
        root.put("markdown", submission.markdown());
        ArrayNode citations = root.putArray("citations");
        for (AgentSubmission.Citation citation : submission.citations()) {
            AgentSubmission.SentMaterial material = byId.get(citation.materialId());
            ObjectNode node = citations.addObject();
            node.put("id", citation.id());
            node.put("materialId", citation.materialId());
            node.put("path", material.path());
            putNullable(node, "side", material.side());
            putNullable(node, "sha", material.sha());
            node.put("startLine", citation.startLine());
            node.put("endLine", citation.endLine());
        }
        ArrayNode unresolved = root.putArray("unresolved");
        submission.unresolved().forEach(unresolved::add);
        return toJson(root);
    }

    private String provenance(List<AgentSubmission.SentMaterial> materials,
                              List<Map<String, Object>> comparisons, int toolCalls) {
        ObjectNode root = mapper.createObjectNode();
        root.put("schemaVersion", "1");
        root.put("toolCalls", toolCalls);
        ArrayNode materialNodes = root.putArray("materials");
        for (AgentSubmission.SentMaterial material : materials) {
            ObjectNode node = materialNodes.addObject();
            node.put("materialId", material.materialId());
            node.put("path", material.path());
            putNullable(node, "side", material.side());
            putNullable(node, "sha", material.sha());
            putNullable(node, "startLine", material.startLine());
            putNullable(node, "endLine", material.endLine());
            putNullable(node, "contentHash", material.contentHash());
        }
        ArrayNode comparisonNodes = root.putArray("comparisons");
        for (Map<String, Object> comparison : comparisons) {
            ObjectNode node = comparisonNodes.addObject();
            comparison.forEach((key, value) -> {
                if (value == null) {
                    node.putNull(key);
                } else if (value instanceof Integer number) {
                    node.put(key, number);
                } else {
                    node.put(key, String.valueOf(value));
                }
            });
        }
        return toJson(root);
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "结果序列化失败");
        }
    }

    private static void putNullable(ObjectNode node, String field, Object value) {
        if (value == null) {
            node.putNull(field);
        } else if (value instanceof Integer number) {
            node.put(field, number);
        } else {
            node.put(field, String.valueOf(value));
        }
    }
}
