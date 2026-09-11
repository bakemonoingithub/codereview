package com.codereview.dto;

import java.time.LocalDateTime;

/**
 * 审查策略响应。
 *
 * <p>与实体 {@link com.codereview.entity.ReviewStrategy} 的唯一区别：
 * **把 {@code params_json} 里的 token 摘掉**。
 *
 * <p>api-review 策略的参数里存着 SonarQube 的只读 token，直接回实体等于把明文凭据
 * 发给浏览器（`GET /api/strategies` 每次列表都发一遍）。与
 * {@link com.codereview.entity.ModelConfig} 的 {@code token}（WRITE_ONLY）保持一致口径。
 *
 * <p>但策略的参数是**一个 JSON 字符串**，不能整体隐藏（界面编辑要用 apiUrl/threshold 等），
 * 所以只能摘掉 token 这个键，另给一个 {@code hasToken} 让界面能显示"已配置、留空不修改"。
 */
public record StrategyResp(Long id, String name, Integer analyzerType, String paramsJson,
                           boolean hasToken, LocalDateTime createdAt, LocalDateTime updatedAt) {
}
