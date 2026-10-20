package com.codereview.agent.execution;

import java.util.Set;

/** 任务与文件状态常量；终态只经带 revision 的条件更新写入。 */
public final class TaskStates {

    public static final String QUEUED = "QUEUED";
    public static final String PREPARING = "PREPARING";
    public static final String RUNNING = "RUNNING";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String PARTIAL = "PARTIAL";
    public static final String FAILED = "FAILED";
    public static final String CANCELLED = "CANCELLED";

    public static final Set<String> TASK_TERMINAL =
            Set.of(SUCCEEDED, PARTIAL, FAILED, CANCELLED);
    public static final Set<String> TARGET_TERMINAL =
            Set.of(SUCCEEDED, PARTIAL, FAILED, CANCELLED);

    public static final String STOP_USER_CANCELLED = "USER_CANCELLED";
    public static final String STOP_TASK_DEADLINE = "TASK_DEADLINE";
    public static final String STOP_INTERRUPTED = "INTERRUPTED";
    public static final String STOP_EXECUTOR_UNAVAILABLE = "EXECUTOR_UNAVAILABLE";
    public static final String STOP_EXECUTION_ERROR = "EXECUTION_ERROR";

    private TaskStates() {
    }

    public static boolean isTerminal(String state) {
        return state != null && TASK_TERMINAL.contains(state);
    }
}
