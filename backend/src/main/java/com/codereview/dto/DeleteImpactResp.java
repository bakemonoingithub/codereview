package com.codereview.dto;

/**
 * 删除项目前的「影响范围」预览：前端先拿它、再弹确认框。
 *
 * <p>为什么要有这个接口：项目删除会**级联销毁它的审查记录与报告**，而报告是可以下载成
 * Markdown 沉淀的"审查结果资产"。让用户在"只知道点一下删除"的情况下销毁这些数据，
 * 是删除功能最不该有的体验 —— 必须先告诉他将失去什么。
 *
 * @param recordCount      将被一并删除的审查记录数
 * @param reportCount      将被一并删除的报告数
 * @param blocked          是否因"有正在进行的审查"而被拒绝删除
 * @param blockReason      拒绝原因（{@code blocked=false} 时为 null）
 * @param thresholdMinutes 阻塞窗口（分钟）：超过该时长仍未结束的审查会被视为已卡死，
 *                         不再阻止删除。前端可据此向用户解释"要等多久"
 */
public record DeleteImpactResp(int recordCount, int reportCount,
                               boolean blocked, String blockReason, int thresholdMinutes) {
}
