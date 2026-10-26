package com.codereview.agent.execution;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 任务调度参数；与模型调用参数分开，便于独立调整。 */
@Data
@Component
@ConfigurationProperties(prefix = "agent.task")
public class TaskProperties {
    private int dispatchQueueCapacity = 64;


    /** 单任务期限（分钟）。 */
    private int deadlineMinutes = 30;
    /** 同时执行的任务数；模型请求并发由 P31 的限流器另行控制。 */
    private int dispatchConcurrency = 4;
    /** 启动时把遗留 QUEUED/RUNNING 收敛为中断；单节点假设。 */
    private boolean recoverOnStart = true;
    /** 期限扫描间隔（毫秒）。 */
    private long sweepMillis = 30_000;
}
