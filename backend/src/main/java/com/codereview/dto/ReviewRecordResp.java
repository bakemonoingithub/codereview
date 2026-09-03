package com.codereview.dto;

import java.time.LocalDateTime;

public record ReviewRecordResp(Long id, Long projectId, String branch, String commitSha,
                               Integer status, Integer progress, String resultJson,
                               LocalDateTime startedAt, LocalDateTime finishedAt, LocalDateTime createdAt) {
}
