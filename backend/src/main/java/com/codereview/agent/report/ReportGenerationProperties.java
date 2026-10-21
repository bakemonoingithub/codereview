package com.codereview.agent.report;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 报告生成参数；容量判断仍由公共调用入口按模型声明的窗口执行。 */
@Data
@Component
@ConfigurationProperties(prefix = "agent.report")
public class ReportGenerationProperties {

    /** 单份报告正文的字符上限；超限明确失败，不静默截断。 */
    private int maxBodyChars = 1_000_000;
    /** 一次报告生成的总期限（分钟）；超时按失败落库，保留冻结来源。 */
    private int generationTimeoutMinutes = 10;
    /** 同时执行的报告生成数；与专项模型并发共用公共调用许可。 */
    private int dispatchConcurrency = 2;
    /** 等待执行的报告数量上限；队列满时明确失败，冻结来源保留。 */
    private int dispatchQueueCapacity = 100;
    /** 报告正文的输出预留（token）；不超过模型声明的最大输出。 */
    private int outputTokens = 16384;
}
