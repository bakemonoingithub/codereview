package com.codereview.agent.execution;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.codereview.agent.db.AgentDatabase;
import com.codereview.agent.entity.AgentReviewTask;
import com.codereview.agent.entity.AgentTaskTarget;
import com.codereview.agent.mapper.AgentReviewTaskMapper;
import com.codereview.agent.mapper.AgentTaskTargetMapper;
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
 * <p>已有成功文件的按 PARTIAL 收敛，没有任何成功文件的按 FAILED；未开始的目标准确标记
 * 为取消，避免列表把它们显示成"仍在排队"。
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
            Long succeeded = db.mapper(AgentTaskTargetMapper.class).selectCount(
                    new LambdaQueryWrapper<AgentTaskTarget>()
                            .eq(AgentTaskTarget::getTaskId, task.getId())
                            .eq(AgentTaskTarget::getState, TaskStates.SUCCEEDED));
            boolean partial = succeeded != null && succeeded > 0;
            if (dispatcher.terminate(task.getId(), partial ? TaskStates.PARTIAL : TaskStates.FAILED,
                    TaskStates.STOP_TASK_DEADLINE)) {
                closed++;
                log.info("任务 {} 到达期限，收敛为 {}", task.getId(), partial ? "PARTIAL" : "FAILED");
            }
        }
        return closed;
    }

}
