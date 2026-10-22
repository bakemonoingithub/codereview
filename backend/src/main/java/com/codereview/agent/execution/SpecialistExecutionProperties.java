package com.codereview.agent.execution;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 专项逐文件执行的调度参数；模型全局/单任务并发仍由公共调用层控制。 */
@Data
@Component
@ConfigurationProperties(prefix = "agent.specialist.execution")
public class SpecialistExecutionProperties {

    /** 同一任务并行执行的文件数；片段共享公共模型并发许可。 */
    private int parallelTargets = 4;
}
