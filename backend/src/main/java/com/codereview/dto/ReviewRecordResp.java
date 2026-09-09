package com.codereview.dto;

import java.time.LocalDateTime;

public record ReviewRecordResp(Long id, Long projectId, Long strategyId, String branch, String commitSha,
                               String scopeJson, Integer status, Integer progress, String resultJson,
                               LocalDateTime startedAt, LocalDateTime finishedAt, LocalDateTime createdAt) {
}
