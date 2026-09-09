package com.codereview.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 审查编排参数（可被 application.yml 的 review.* 覆盖）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "review")
public class ReviewProperties {
    /** 并行限流并发数 */
    private int concurrency = 4;
    /** 分块阈值（字符数）；超过才按类/方法切分。默认约 4 万 token（字符/3.5），占窗口 70% 左右 */
    private int chunkMaxChars = 140_000;
    /** 单元级最大重试次数（1 次初始 + retryMax 次重试） */
    private int retryMax = 3;
    /** 指数退避基数（毫秒）：1s→2s→4s */
    private long retryBaseMillis = 1000;
}
