package com.codereview.agent.execution;

import com.codereview.agent.api.ApiException;
import com.codereview.agent.api.ErrorCode;
import com.codereview.agent.db.AgentDatabase;
import com.codereview.agent.entity.AgentReviewTask;
import com.codereview.agent.mapper.AgentReviewTaskMapper;
import com.codereview.agent.support.AgentTime;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 执行前与执行中的公共检查。
 *
 * <p>取消和期限都可能发生在任意调用之间；执行器在每次新调用前调用这里，
 * 不能只依赖终态更新来"事后补救"。
 */
@Service
@ConditionalOnProperty(prefix = "agent.datasource", name = "url")
public class TaskExecutionGuard {

    private final AgentDatabase db;

    public TaskExecutionGuard(AgentDatabase db) {
        this.db = db;
    }

    public AgentReviewTask requireActive(String taskId) {
        AgentReviewTask task = db.mapper(AgentReviewTaskMapper.class).selectById(taskId);
        if (task == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, "任务不存在：" + taskId);
        }
        if (task.getCancelRequestedAt() != null || TaskStates.CANCELLED.equals(task.getState())) {
            throw new ApiException(ErrorCode.TASK_CANCELLED, "任务已取消，停止新的调用");
        }
        if (TaskStates.isTerminal(task.getState())) {
            throw new ApiException(ErrorCode.TASK_CANCELLED, "任务已结束，停止新的调用");
        }
        if (task.getDeadlineAt() != null && AgentTime.now().isAfter(task.getDeadlineAt())) {
            throw new ApiException(ErrorCode.TASK_DEADLINE, "任务已到达期限，停止新的调用");
        }
        return task;
    }

    public boolean isCancelled(String taskId) {
        AgentReviewTask task = db.mapper(AgentReviewTaskMapper.class).selectById(taskId);
        return task != null && (task.getCancelRequestedAt() != null
                || TaskStates.CANCELLED.equals(task.getState()));
    }
}
