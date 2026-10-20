package com.codereview.agent.execution;

import com.codereview.agent.entity.AgentReviewTask;

/**
 * 公共执行器接口。
 *
 * <p>P30 只提供调度、取消和终态保护；专项执行由 P31 实现本接口后接入，
 * 报告与 Agent 继续复用同一任务/调用/结果模型。执行器返回最终任务状态，
 * 调度层不替它推断"所有文件都成功"。
 */
public interface TaskWorker {

    TaskOutcome execute(AgentReviewTask task);

    record TaskOutcome(String state, String stopReason) {

        public TaskOutcome {
            if (!TaskStates.TASK_TERMINAL.contains(state)) {
                throw new IllegalArgumentException("执行器只能返回终态：" + state);
            }
        }

        public static TaskOutcome succeeded() {
            return new TaskOutcome(TaskStates.SUCCEEDED, null);
        }

        public static TaskOutcome partial(String stopReason) {
            return new TaskOutcome(TaskStates.PARTIAL, stopReason);
        }

        public static TaskOutcome failed(String stopReason) {
            return new TaskOutcome(TaskStates.FAILED, stopReason);
        }
    }
}
