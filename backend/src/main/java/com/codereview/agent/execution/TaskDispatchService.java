package com.codereview.agent.execution;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.codereview.agent.api.ApiException;
import com.codereview.agent.api.ErrorCode;
import com.codereview.agent.db.AgentDatabase;
import com.codereview.agent.entity.AgentReviewTask;
import com.codereview.agent.entity.AgentTaskTarget;
import com.codereview.agent.mapper.AgentReviewTaskMapper;
import com.codereview.agent.mapper.AgentTaskTargetMapper;
import com.codereview.agent.support.AgentTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.annotation.PreDestroy;

/**
 * 任务调度：有界并发、启动条件更新、终态保护。
 *
 * <p>P30 不实现专项执行；通过 {@link TaskWorker} 接入。没有执行器时任务明确失败为
 * {@code EXECUTOR_UNAVAILABLE}，而不是永远停在 QUEUED 假装在跑。
 *
 * <p>终态写入必须带前置状态条件：取消或期限先落库后，迟到的成功回调更新 0 行，
 * 不能把已完成的任务改回成功。
 */
@Service
@ConditionalOnProperty(prefix = "agent.datasource", name = "url")
public class TaskDispatchService {

    private static final Logger log = LoggerFactory.getLogger(TaskDispatchService.class);

    private final AgentDatabase db;
    private final ObjectProvider<TaskWorker> workers;
    private final ExecutorService pool;

    public TaskDispatchService(AgentDatabase db, ObjectProvider<TaskWorker> workers,
                               TaskProperties properties) {
        this.db = db;
        this.workers = workers;
        this.pool = Executors.newFixedThreadPool(Math.max(1, properties.getDispatchConcurrency()),
                new NamedThreadFactory());
    }

    public void dispatch(String taskId) {
        try {
            pool.execute(() -> run(taskId));
        } catch (RejectedExecutionException e) {
            log.warn("任务调度队列已满，任务 {} 标记失败", taskId);
            terminateQueued(taskId, "DISPATCH_REJECTED");
        }
    }

    void run(String taskId) {
        AgentReviewTask task = db.mapper(AgentReviewTaskMapper.class).selectById(taskId);
        if (task == null || !TaskStates.QUEUED.equals(task.getState())) {
            return;
        }
        if (!markRunning(task)) {
            return;
        }
        TaskWorker worker = workers.getIfUnique();
        if (worker == null) {
            finish(taskId, TaskStates.FAILED, TaskStates.STOP_EXECUTOR_UNAVAILABLE);
            return;
        }
        try {
            TaskWorker.TaskOutcome outcome = worker.execute(task);
            finish(taskId, outcome.state(), outcome.stopReason());
        } catch (ApiException e) {
            if (e.errorCode() == ErrorCode.TASK_CANCELLED) {
                finish(taskId, TaskStates.CANCELLED, TaskStates.STOP_USER_CANCELLED);
            } else if (e.errorCode() == ErrorCode.TASK_DEADLINE) {
                finishForDeadline(taskId);
            } else {
                finish(taskId, TaskStates.FAILED, e.errorCode().code());
            }
        } catch (RuntimeException e) {
            log.error("任务 {} 执行异常", taskId, e);
            finish(taskId, TaskStates.FAILED, TaskStates.STOP_EXECUTION_ERROR);
        }
    }

    private boolean markRunning(AgentReviewTask task) {
        int affected = db.mapper(AgentReviewTaskMapper.class).update(null,
                new LambdaUpdateWrapper<AgentReviewTask>()
                        .eq(AgentReviewTask::getId, task.getId())
                        .eq(AgentReviewTask::getState, TaskStates.QUEUED)
                        .eq(AgentReviewTask::getRevision, task.getRevision())
                        .set(AgentReviewTask::getState, TaskStates.RUNNING)
                        .set(AgentReviewTask::getStartedAt, AgentTime.now())
                        .set(AgentReviewTask::getUpdatedAt, AgentTime.now())
                        .setSql("revision = revision + 1"));
        return affected > 0;
    }

    /**
     * 写入终态。只有仍处于 RUNNING 的任务能被本次回调结束；
     * 成功还要求没有取消请求，避免取消与成功竞争时取消先落库却被覆盖。
     */
    public boolean finish(String taskId, String state, String stopReason) {
        if (!TaskStates.isTerminal(state)) {
            throw new IllegalArgumentException("终态不合法：" + state);
        }
        LambdaUpdateWrapper<AgentReviewTask> update = new LambdaUpdateWrapper<AgentReviewTask>()
                .eq(AgentReviewTask::getId, taskId)
                .eq(AgentReviewTask::getState, TaskStates.RUNNING)
                .set(AgentReviewTask::getState, state)
                .set(AgentReviewTask::getStopReason, stopReason)
                .set(AgentReviewTask::getFinishedAt, AgentTime.now())
                .set(AgentReviewTask::getUpdatedAt, AgentTime.now())
                .setSql("revision = revision + 1");
        if (TaskStates.SUCCEEDED.equals(state)) {
            update.isNull(AgentReviewTask::getCancelRequestedAt);
        }
        return db.inTransaction(() -> {
            if (db.mapper(AgentReviewTaskMapper.class).update(null, update) > 0) {
                closeOpenTargets(taskId, state, stopReason);
                return true;
            }
            log.info("任务 {} 的终态 {} 未被写入：任务已取消或已结束", taskId, state);
            return false;
        });
    }

    public void finishForDeadline(String taskId) {
        Long succeeded = db.mapper(AgentTaskTargetMapper.class).selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AgentTaskTarget>()
                        .eq(AgentTaskTarget::getTaskId, taskId)
                        .eq(AgentTaskTarget::getState, TaskStates.SUCCEEDED));
        terminate(taskId, succeeded != null && succeeded > 0 ? TaskStates.PARTIAL : TaskStates.FAILED,
                TaskStates.STOP_TASK_DEADLINE);
    }

    /**
     * 结束仍处于 QUEUED 或 RUNNING 的任务；返回是否由本次调用写入。
     * 取消、期限和重启收敛共用这一条条件更新。
     */
    public boolean terminate(String taskId, String state, String stopReason) {
        if (!TaskStates.isTerminal(state)) {
            throw new IllegalArgumentException("终态不合法：" + state);
        }
        return db.inTransaction(() -> {
            boolean changed = db.mapper(AgentReviewTaskMapper.class).update(null,
                new LambdaUpdateWrapper<AgentReviewTask>()
                        .eq(AgentReviewTask::getId, taskId)
                        .in(AgentReviewTask::getState, TaskStates.QUEUED, TaskStates.RUNNING)
                        .set(AgentReviewTask::getState, state)
                        .set(AgentReviewTask::getStopReason, stopReason)
                        .set(AgentReviewTask::getFinishedAt, AgentTime.now())
                        .set(AgentReviewTask::getUpdatedAt, AgentTime.now())
                        .setSql("revision = revision + 1")) > 0;
            if (changed) {
                closeOpenTargets(taskId, state, stopReason);
            }
            return changed;
        });
    }

    private void terminateQueued(String taskId, String stopReason) {
        db.inTransaction(() -> {
            int changed = db.mapper(AgentReviewTaskMapper.class).update(null,
                new LambdaUpdateWrapper<AgentReviewTask>()
                        .eq(AgentReviewTask::getId, taskId)
                        .eq(AgentReviewTask::getState, TaskStates.QUEUED)
                        .set(AgentReviewTask::getState, TaskStates.FAILED)
                        .set(AgentReviewTask::getStopReason, stopReason)
                        .set(AgentReviewTask::getFinishedAt, AgentTime.now())
                        .set(AgentReviewTask::getUpdatedAt, AgentTime.now())
                        .setSql("revision = revision + 1"));
            if (changed > 0) {
                closeOpenTargets(taskId, TaskStates.FAILED, stopReason);
            }
            return null;
        });
    }

    /** 仅收敛尚未完成的目标；已发布的成功或失败结果不会被重写。 */
    private void closeOpenTargets(String taskId, String state, String stopReason) {
        if (TaskStates.SUCCEEDED.equals(state)) {
            return;
        }
        String targetState = TaskStates.CANCELLED.equals(state) || TaskStates.PARTIAL.equals(state)
                || TaskStates.STOP_TASK_DEADLINE.equals(stopReason)
                ? TaskStates.CANCELLED : TaskStates.FAILED;
        db.mapper(AgentTaskTargetMapper.class).update(null,
                new LambdaUpdateWrapper<AgentTaskTarget>()
                        .eq(AgentTaskTarget::getTaskId, taskId)
                        .notIn(AgentTaskTarget::getState, TaskStates.TARGET_TERMINAL)
                        .set(AgentTaskTarget::getState, targetState)
                        .set(AgentTaskTarget::getErrorCode, stopReason)
                        .set(AgentTaskTarget::getUpdatedAt, AgentTime.now())
                        .setSql("revision = revision + 1"));
    }

    @PreDestroy
    public void shutdown() {
        pool.shutdownNow();
    }

    private static final class NamedThreadFactory implements ThreadFactory {

        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "agent-task-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
