package com.codereview.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 报告生成参数（可被 application.yml 的 {@code report.*} 覆盖）。
 *
 * <p>为什么单独一组配置，而不是复用 {@code review.chunk-max-chars}：
 * 「审查单文件分块」与「报告聚合多条记录」是两个不同场景 —— 前者约束**发送给模型的代码**，
 * 后者约束**多条记录的审查结果 JSON**。共用一个旋钮，以后想单独调就会互相打架。
 */
@Data
@Component
@ConfigurationProperties(prefix = "report")
public class ReportProperties {

    /**
     * 单次报告生成的 user prompt 字符上限（**含**报告模板、用户补充要求与聚合结果）。
     *
     * <p>口径沿用本仓既有约定：字符数 / 3.5 ≈ token，14 万字符 ≈ 4 万 token。
     * 这是"能把多少条审查记录塞进一次调用"的硬上限 —— 原先完全没有约束，
     * 勾选记录一多就无界增长，最后失败在网关侧（错误信息还不会指向根因）。
     */
    private int promptMaxChars = 140_000;

    /**
     * 单条审查记录在聚合里的字符上限；超出就截断（{@code result_json} 里
     * {@code summary} 在最前，所以先保留到的是最有价值的部分）。
     */
    private int recordMaxChars = 20_000;
}
