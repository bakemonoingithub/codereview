package com.codereview.agent.report;

import java.util.Set;

/** 报告状态；INPUT_FROZEN 之后才允许发起模型生成，终态不再被迟到回调覆盖。 */
public final class ReportStates {

    public static final String INPUT_FROZEN = "INPUT_FROZEN";
    public static final String QUEUED = "QUEUED";
    public static final String RUNNING = "RUNNING";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String FAILED = "FAILED";

    public static final Set<String> TERMINAL = Set.of(SUCCEEDED, FAILED);

    private ReportStates() {
    }

    public static boolean isTerminal(String state) {
        return TERMINAL.contains(state);
    }
}
