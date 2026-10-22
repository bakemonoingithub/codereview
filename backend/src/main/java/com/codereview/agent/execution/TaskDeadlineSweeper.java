package com.codereview.agent.execution;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.codereview.agent.db.AgentDatabase;
import com.codereview.agent.entity.AgentReviewTask;
import com.codereview.agent.entity.AgentRun;
import com.codereview.agent.entity.AgentTaskTarget;
import com.codereview.agent.entity.AgentToolCall;
import com.codereview.agent.mapper.AgentReviewTaskMapper;
import com.codereview.agent.mapper.AgentRunMapper;
import com.codereview.agent.mapper.AgentTaskTargetMapper;
import com.codereview.agent.mapper.AgentToolCallMapper;
import com.codereview.agent.support.AgentTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 任务期限扫描：到点后停止新调用，并结束仍未完成的任务。
 *
 * <p>专项任务已有成功文件时按 PARTIAL 收敛，否则按 FAILED。Agent 任务没有文件目标，
 * 改为看是否已经留下工具调查：有则 PARTIAL，与执行循环的限制终止同一口径。
 */
@Service
@ConditionalOnProperty(prefix = "agent.datasource", name = "url")
public class TaskDeadlineSweeper {

    private static final Logger log = LoggerFactory.getLogger(TaskDeadlineSweeper.class);
    private static final int BATCH = 100;

    private final AgentDatabase db;
    private final TaskDispatchService dispatcher;

    public TaskDeadlineSweeper(AgentDatabase db, TaskDispatchService dispatcher) {
        this.db = db;
        this.dispatcher = dispatcher;
    }

    @Scheduled(fixedDelayString = "${agent.task.sweep-millis:30000}")
    public int sweep() {
        List<AgentReviewTask> expired = db.mapper(AgentReviewTaskMapper.class).selectList(
                new LambdaQueryWrapper<AgentReviewTask>()
                        .in(AgentReviewTask::getState, TaskStates.QUEUED, TaskStates.RUNNING)
                        .lt(AgentReviewTask::getDeadlineAt, AgentTime.now())
                        .last("limit " + BATCH));
        int closed = 0;
        for (AgentReviewTask task : expired) {
            boolean partial = "AGENT".equals(task.getRoute())
                    ? agentInvestigated(task.getId())
                    : succeededTargets(task.getId()) > 0;
            if (dispatcher.terminate(task.getId(), partial ? TaskStates.PARTIAL : TaskStates.FAILED,
                    TaskStates.STOP_TASK_DEADLINE)) {
                closed++;
                log.info("任务 {} 到达期限，收敛为 {}", task.getId(), partial ? "PARTIAL" : "FAILED");
            }
        }
        return closed;
    }

    private long succeededTargets(String taskId) {
        Long succeeded = db.mapper(AgentTaskTargetMapper.class).selectCount(
                new LambdaQueryWrapper<AgentTaskTarget>()
                        .eq(AgentTaskTarget::getTaskId, taskId)
                        .eq(AgentTaskTarget::getState, TaskStates.SUCCEEDED));
        return succeeded == null ? 0 : succeeded;
    }

    /** 与 {@link AgentTaskWorker} 的限制终止一致：有工具尝试或已落库的工具调用即算调查过。 */
    private boolean agentInvestigated(String taskId) {
        AgentRun run = db.mapper(AgentRunMapper.class).selectById(taskId);
        if (run != null && run.getToolAttempts() != null && run.getToolAttempts() > 0) {
            return true;
        }
        Long calls = db.mapper(AgentToolCallMapper.class).selectCount(
                new LambdaQueryWrapper<AgentToolCall>()
                        .eq(AgentToolCall::getTaskId, taskId)
                        .ne(AgentToolCall::getStatus, "REJECTED_BATCH"));
        return calls != null && calls > 0;
    }

}
