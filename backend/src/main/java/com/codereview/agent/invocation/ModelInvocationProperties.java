package com.codereview.agent.invocation;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 公共调用参数。
 *
 * <p>传输重试只覆盖明确的暂态失败（429、5xx、连接失败），且每步最多额外一次；
 * 协议重试由调用方按契约另行控制，不能在这里再放大一轮。
 */
@Data
@Component
@ConfigurationProperties(prefix = "agent.model")
public class ModelInvocationProperties {
    private int connectTimeoutSeconds = 10;
    private int probeTimeoutSeconds = 15;
    private int probeConnectTimeoutSeconds = 5;
    private int safetyReserveMinTokens = 1024;
    private double safetyReserveRatio = 0.1;


    /** 暂态传输重试次数（不含首次）。契约固定为最多额外 1 次。 */
    private int transportRetries = 1;
    /** 退避基数；429 优先使用 Retry-After，但同样受这个上限约束。 */
    private long retryBaseMillis = 500;
    /** 单次响应读超时上限（秒）；任务剩余期限更短时取更短者。 */
    private int maxReadTimeoutSeconds = 180;
    /** 非验证调用的默认输出预留（token）；不超过模型最大输出。 */
    private int outputReserveTokens = 4096;
    /** 验证调用的输出预留：只证明可达与 JSON 结构。 */
    private int validationOutputTokens = 64;
    /** 单任务全部真实尝试的硬上限；与 P30.6 建任务时的额度一致。 */
    private int taskCallLimit = 200;
    /** 全局模型请求并发上限。 */
    private int globalConcurrency = 4;
    /** 同一任务占用的模型请求并发上限。 */
    private int perTaskConcurrency = 2;
}
