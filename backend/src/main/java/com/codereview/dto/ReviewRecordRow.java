package com.codereview.dto;

import java.time.LocalDateTime;

/**
 * 审查记录**列表行**（`GET /projects/{id}/reviews` 的 records 元素）。
 *
 * 刻意不包含三个大字段 —— 它们是列表接口的历史包袱：
 * <ul>
 *   <li>{@code resultJson}：每个单元的 LLM 原文都在里面，单条可达 MB 级；列表翻页会把它们反复搬运。
 *       需要完整结果请走 {@code GET /reviews/{id}}（返回 {@link ReviewRecordResp}）。</li>
 *   <li>{@code scopeJson}：只有详情视图用得上（审查范围展示）。</li>
 *   <li>{@code strategySnapshotJson}：**内含模型的明文 apiKey**（见 ReviewService#buildSnapshot），
 *       绝不该出现在列表响应里。</li>
 * </ul>
 * {@code strategyName} 是列表查询顺带 join 出来的：列表要显示"用了哪个策略"，
 * 而记录里只存 id，前端不该为此再拉一次策略字典。
 *
 * id 为 Long：Jackson 全局配置把 Long 序列化成字符串（雪花 id 避免 JS 精度丢失），
 * 与 {@link ReviewRecordResp} 保持一致。
 */
public record ReviewRecordRow(Long id, Long projectId, Long strategyId, String strategyName, String branch,
                              String commitSha, Integer status, Integer progress,
                              LocalDateTime startedAt, LocalDateTime finishedAt, LocalDateTime createdAt) {
}
