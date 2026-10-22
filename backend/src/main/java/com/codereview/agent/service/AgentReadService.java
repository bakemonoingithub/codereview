package com.codereview.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.codereview.agent.api.ApiException;
import com.codereview.agent.api.ErrorCode;
import com.codereview.agent.api.PageResponse;
import com.codereview.agent.db.AgentDatabase;
import com.codereview.agent.dto.AgentReadDtos;
import com.codereview.agent.execution.TaskStates;
import com.codereview.agent.entity.AgentReviewResult;
import com.codereview.agent.entity.AgentReviewTask;
import com.codereview.agent.entity.AgentRun;
import com.codereview.agent.entity.AgentToolCall;
import com.codereview.agent.mapper.AgentReviewResultMapper;
import com.codereview.agent.mapper.AgentReviewTaskMapper;
import com.codereview.agent.mapper.AgentRunMapper;
import com.codereview.agent.mapper.AgentToolCallMapper;
import com.codereview.agent.support.AgentIds;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 读取侧：进度、逐次工具调用与已发布结果。
 *
 * <p>所有入口先确认任务属于 Agent 路线，再按 taskId 过滤；工具参数与 envelope 只在单次
 * 详情返回，列表不放大正文。材料正文继续复用公共任务材料端点。
 */
@Service
@ConditionalOnProperty(prefix = "agent.datasource", name = "url")
public class AgentReadService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final AgentDatabase db;
    private final ObjectMapper mapper;

    public AgentReadService(AgentDatabase db, ObjectMapper mapper) {
        this.db = db;
        this.mapper = mapper;
    }

    public AgentReadDtos.Progress progress(String taskId) {
        AgentReviewTask task = requireAgentTask(taskId);
        AgentRun run = db.mapper(AgentRunMapper.class).selectById(taskId);
        JsonNode config = readJson(task.getConfigSnapshot());
        JsonNode limits = config.path("limits");
        int turnNo = run == null || run.getTurnNo() == null ? 0 : run.getTurnNo();
        int toolAttempts = run == null || run.getToolAttempts() == null ? 0 : run.getToolAttempts();
        int networkAttempts = run == null || run.getNetworkAttempts() == null
                ? 0 : run.getNetworkAttempts();
        // 任务行是终态的唯一事实：重启收敛或结果发布可能已经结束任务，
        // 而 agent_run 仍停在 RUNNING。进度不能把这种过期运行显示成仍在执行。
        boolean taskClosed = TaskStates.isTerminal(task.getState());
        String state = !taskClosed && run != null && run.getState() != null
                ? run.getState() : task.getState();
        String stopReason = !taskClosed && run != null ? run.getStopReason() : task.getStopReason();
        return new AgentReadDtos.Progress(taskId, state, stopReason, turnNo, toolAttempts,
                networkAttempts, run == null ? null : run.getCurrentAction(),
                readJson(run == null ? null : run.getCoveredJson()), limits,
                run == null ? task.getUpdatedAt() : run.getUpdatedAt());
    }

    public PageResponse<AgentReadDtos.ToolCallSummary> toolCalls(String taskId, Integer pageNum,
                                                                 Integer pageSize) {
        requireAgentTask(taskId);
        int page = pageNum == null ? 1 : pageNum;
        int size = pageSize == null ? DEFAULT_PAGE_SIZE : pageSize;
        if (page < 1 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "页码须为正整数，每页最多 " + MAX_PAGE_SIZE + " 条");
        }
        AgentToolCallMapper callMapper = db.mapper(AgentToolCallMapper.class);
        long total = callMapper.selectCount(new LambdaQueryWrapper<AgentToolCall>()
                .eq(AgentToolCall::getTaskId, taskId));
        List<AgentToolCall> rows = callMapper.selectList(new LambdaQueryWrapper<AgentToolCall>()
                .eq(AgentToolCall::getTaskId, taskId)
                .orderByAsc(AgentToolCall::getTurnNo)
                .orderByAsc(AgentToolCall::getOrdinal)
                .last("limit " + size + " offset " + (long) (page - 1) * size));
        List<AgentReadDtos.ToolCallSummary> items = new ArrayList<>();
        for (AgentToolCall row : rows) {
            items.add(new AgentReadDtos.ToolCallSummary(row.getId(),
                    row.getTurnNo() == null ? 0 : row.getTurnNo(),
                    row.getOrdinal() == null ? 0 : row.getOrdinal(),
                    row.getToolName(), row.getStatus(), row.getStartedAt(), row.getFinishedAt()));
        }
        boolean hasMore = (long) page * size < total;
        return new PageResponse<>(items, null, hasMore, total, page, size);
    }

    public AgentReadDtos.ToolCallDetail toolCall(String taskId, String callId) {
        requireAgentTask(taskId);
        AgentToolCall row = db.mapper(AgentToolCallMapper.class)
                .selectById(AgentIds.requireValid(callId, "工具调用 ID"));
        if (row == null || !taskId.equals(row.getTaskId())) {
            throw new ApiException(ErrorCode.NOT_FOUND, "工具调用不属于当前任务：" + callId);
        }
        List<String> materialIds = new ArrayList<>();
        for (JsonNode node : readJson(row.getMaterialIds())) {
            materialIds.add(node.asText());
        }
        return new AgentReadDtos.ToolCallDetail(row.getId(),
                row.getTurnNo() == null ? 0 : row.getTurnNo(),
                row.getOrdinal() == null ? 0 : row.getOrdinal(), row.getToolName(),
                row.getProviderToolCallId(), row.getArguments(), readJson(row.getEnvelope()),
                materialIds, row.getStatus(), row.getStartedAt(), row.getFinishedAt());
    }

    public AgentReadDtos.Result result(String taskId) {
        requireAgentTask(taskId);
        List<AgentReviewResult> rows = db.mapper(AgentReviewResultMapper.class).selectList(
                new LambdaQueryWrapper<AgentReviewResult>()
                        .eq(AgentReviewResult::getTaskId, taskId)
                        .eq(AgentReviewResult::getKind, "AGENT")
                        .last("limit 1"));
        if (rows.isEmpty()) {
            throw new ApiException(ErrorCode.NOT_FOUND, "该任务尚未发布完整结果：" + taskId);
        }
        AgentReviewResult row = rows.get(0);
        return new AgentReadDtos.Result(row.getId(), row.getSchemaVersion(), row.getCompleteness(),
                row.getCreatedAt(), readJson(row.getBody()), readJson(row.getProvenance()));
    }

    private AgentReviewTask requireAgentTask(String taskId) {
        AgentReviewTask task = db.mapper(AgentReviewTaskMapper.class)
                .selectById(AgentIds.requireValid(taskId, "任务 ID"));
        if (task == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, "任务不存在：" + taskId);
        }
        if (!"AGENT".equals(task.getRoute())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "该任务不是 Agent 任务：" + taskId);
        }
        return task;
    }

    private JsonNode readJson(String value) {
        if (value == null || value.isBlank()) {
            return mapper.createObjectNode();
        }
        try {
            return mapper.readTree(value);
        } catch (Exception e) {
            return mapper.createObjectNode();
        }
    }
}
