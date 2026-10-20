package com.codereview.agent.execution;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.codereview.agent.db.AgentDatabase;
import com.codereview.agent.entity.AgentModelCall;
import com.codereview.agent.entity.AgentReviewTask;
import com.codereview.agent.entity.AgentTaskTarget;
import com.codereview.agent.mapper.AgentModelCallMapper;
import com.codereview.agent.mapper.AgentReviewTaskMapper;
import com.codereview.agent.mapper.AgentTaskTargetMapper;
import com.codereview.agent.support.AgentTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 启动收敛：单节点重启后，遗留的 QUEUED/RUNNING 任务与调用明确结束，不恢复原执行。
 *
 * <p>多节点部署时不能用这个默认行为（会把别的节点正在跑的任务标成中断），
 * 必须通过 {@code agent.task.recover-on-start=false} 关闭并改用租约机制。
 */
@Component
@ConditionalOnProperty(prefix = "agent.datasource", name = "url")
public class TaskRecoveryRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TaskRecoveryRunner.class);

    private final AgentDatabase db;
    private final TaskProperties properties;

    public TaskRecoveryRunner(AgentDatabase db, TaskProperties properties) {
        this.db = db;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isRecoverOnStart()) {
            return;
        }
        RecoveryResult result = recover();
        if (result.tasks > 0 || result.calls > 0) {
            log.warn("启动收敛：{} 个任务、{} 个目标、{} 条调用被标记为中断",
                    result.tasks, result.targets, result.calls);
        }
    }

    public RecoveryResult recover() {
        var now = AgentTime.now();
        int tasks = db.mapper(AgentReviewTaskMapper.class).update(null,
                new LambdaUpdateWrapper<AgentReviewTask>()
                        .in(AgentReviewTask::getState, TaskStates.QUEUED, TaskStates.RUNNING)
                        .set(AgentReviewTask::getState, TaskStates.FAILED)
                        .set(AgentReviewTask::getStopReason, TaskStates.STOP_INTERRUPTED)
                        .set(AgentReviewTask::getFinishedAt, now)
                        .set(AgentReviewTask::getUpdatedAt, now)
                        .setSql("revision = revision + 1"));
        int runningTargets = db.mapper(AgentTaskTargetMapper.class).update(null,
                new LambdaUpdateWrapper<AgentTaskTarget>()
                        .in(AgentTaskTarget::getState, TaskStates.PREPARING, TaskStates.RUNNING)
                        .set(AgentTaskTarget::getState, TaskStates.FAILED)
                        .set(AgentTaskTarget::getErrorCode, TaskStates.STOP_INTERRUPTED)
                        .set(AgentTaskTarget::getUpdatedAt, now)
                        .setSql("revision = revision + 1"));
        int queuedTargets = db.mapper(AgentTaskTargetMapper.class).update(null,
                new LambdaUpdateWrapper<AgentTaskTarget>()
                        .eq(AgentTaskTarget::getState, TaskStates.QUEUED)
                        .set(AgentTaskTarget::getState, TaskStates.CANCELLED)
                        .set(AgentTaskTarget::getErrorCode, TaskStates.STOP_INTERRUPTED)
                        .set(AgentTaskTarget::getUpdatedAt, now)
                        .setSql("revision = revision + 1"));
        int calls = db.mapper(AgentModelCallMapper.class).update(null,
                new LambdaUpdateWrapper<AgentModelCall>()
                        .eq(AgentModelCall::getState, "RUNNING")
                        .set(AgentModelCall::getState, "FAILED")
                        .set(AgentModelCall::getErrorCode, TaskStates.STOP_INTERRUPTED)
                        .set(AgentModelCall::getErrorMessage, "进程重启，调用未完成")
                        .set(AgentModelCall::getFinishedAt, now));
        return new RecoveryResult(tasks, runningTargets + queuedTargets, calls);
    }

    public record RecoveryResult(int tasks, int targets, int calls) {
    }
}
