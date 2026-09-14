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
    /**
     * 删除项目时的「执行中审查」阻塞窗口（分钟）。
     *
     * <p>存在**排队中或执行中**、且创建时间距现在不足该窗口的审查记录时，拒绝删除该项目；
     * 超过窗口则视为任务已卡死，允许删除（后台线程的写回自然失败）。
     *
     * <p>为什么按 {@code created_at} 而不是 {@code started_at} 计算：排队阶段的
     * {@code started_at} 仍为 NULL，用它会导致"卡在队列里"的任务永远拦不住，与规则意图相反。
     *
     * <p>做成配置项而不是常量，是为了演示时能临时调小 —— 否则"允许删除"这条路径
     * 只能真等一小时才能展示。
     */
    private int deleteBlockMinutes = 60;
}
