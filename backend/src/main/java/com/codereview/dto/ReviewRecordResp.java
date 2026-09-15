package com.codereview.dto;

import java.time.LocalDateTime;

public record ReviewRecordResp(Long id, Long projectId, Long strategyId, String branch, String commitSha,
                               String scopeJson, Integer status, Integer progress, String resultJson,
                               // 失败原因（有界列）：失败时 resultJson 可能为空、也可能仍是上一次成功的结果，
                               // 界面靠这一列说明"为什么失败"，不再从结果 JSON 的 summary 里猜
                               String errorMessage,
                               LocalDateTime startedAt, LocalDateTime finishedAt, LocalDateTime createdAt) {
}
