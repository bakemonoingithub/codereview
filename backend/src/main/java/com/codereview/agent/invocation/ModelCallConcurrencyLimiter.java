package com.codereview.agent.invocation;

import com.codereview.agent.api.ApiException;
import com.codereview.agent.api.ErrorCode;
import com.codereview.agent.support.AgentTime;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 模型请求的公共并发：全局最多 4 个，同一任务最多 2 个。
 *
 * <p>等待计入任务期限；期限不足时直接停止，不无限排队。许可在 HTTP 返回后释放，
 * 不覆盖解析与落库时间。
 */
@Service
public class ModelCallConcurrencyLimiter {

    private final Semaphore global;
    private final int perTask;
    private final ConcurrentHashMap<String, Semaphore> taskLimits = new ConcurrentHashMap<>();

    public ModelCallConcurrencyLimiter(ModelInvocationProperties properties) {
        this.global = new Semaphore(Math.max(1, properties.getGlobalConcurrency()), true);
        this.perTask = Math.max(1, properties.getPerTaskConcurrency());
    }

    public Permit acquire(String taskId, LocalDateTime deadlineAt) {
        long waitMillis = waitMillis(deadlineAt);
        try {
            if (!global.tryAcquire(waitMillis, TimeUnit.MILLISECONDS)) {
                throw busy(taskId);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(ErrorCode.MODEL_UNAVAILABLE, "等待模型并发许可被中断");
        }
        if (taskId == null) {
            return new Permit(global, null);
        }
        Semaphore taskSemaphore = taskLimits.computeIfAbsent(taskId, ignored -> new Semaphore(perTask, true));
        boolean taskAcquired = false;
        try {
            taskAcquired = taskSemaphore.tryAcquire(waitMillis(deadlineAt), TimeUnit.MILLISECONDS);
            if (!taskAcquired) {
                throw busy(taskId);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(ErrorCode.MODEL_UNAVAILABLE, "等待模型并发许可被中断");
        } finally {
            if (!taskAcquired) {
                global.release();
            }
        }
        return new Permit(global, taskSemaphore);
    }

    public int availableGlobalPermits() {
        return global.availablePermits();
    }

    private static long waitMillis(LocalDateTime deadlineAt) {
        if (deadlineAt == null) {
            return 30_000;
        }
        long remaining = Duration.between(AgentTime.now(), deadlineAt).toMillis();
        if (remaining <= 0) {
            throw new ApiException(ErrorCode.TASK_DEADLINE, "任务已到达期限，未等待模型并发许可");
        }
        return Math.min(remaining, 180_000);
    }

    private static ApiException busy(String taskId) {
        return new ApiException(ErrorCode.TASK_DEADLINE,
                "模型并发已满且任务剩余期限不足，未发送请求",
                Map.of("taskId", taskId == null ? "" : taskId));
    }

    /** 释放顺序与获取顺序相反；重复关闭无副作用。 */
    public static final class Permit implements AutoCloseable {

        private final Semaphore global;
        private final Semaphore task;
        private boolean closed;

        private Permit(Semaphore global, Semaphore task) {
            this.global = global;
            this.task = task;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (task != null) {
                task.release();
            }
            if (global != null) {
                global.release();
            }
        }
    }
}
