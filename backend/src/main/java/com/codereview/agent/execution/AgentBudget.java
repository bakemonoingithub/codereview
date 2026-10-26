package com.codereview.agent.execution;

/**
 * Agent 首版运行预算（P33.1 实验确定）。
 *
 * <p>这里只保留历史版本的缺省值；新任务由 AgentBudgetProperties 读取可覆盖配置，
 * 发起任务时冻结进 {@code configSnapshot.limits}；执行层只读快照，
 * 不在运行中重新取配置。改值属于行为变更，需要同步设计文档与验证。
 */
public final class AgentBudget {

    /** 模型轮次上限。 */
    public static final int MODEL_TURNS = 48;
    /** 历史网络尝试缺省上限；新任务还校验公共 task-call-limit（默认 200 次）。 */
    public static final int NETWORK_ATTEMPTS = 96;
    /** 工具执行尝试上限；错误参数也计数。 */
    public static final int TOOL_ATTEMPTS = 192;
    /** 任务期限（分钟）。 */
    public static final int DEADLINE_MINUTES = 20;
    /** 每轮输出预留 token。 */
    public static final int OUTPUT_TOKENS = 16384;
    /** 单次模型调用超时（秒）。 */
    public static final int MODEL_TIMEOUT_SECONDS = 120;
    /** 上下文终止阈值：输入估算达到窗口的比例。 */
    public static final double CONTEXT_STOP_RATIO = 0.8;
    /** 连续无进展轮次上限。 */
    public static final int NO_PROGRESS_TURNS = 3;

    private AgentBudget() {
    }
}
