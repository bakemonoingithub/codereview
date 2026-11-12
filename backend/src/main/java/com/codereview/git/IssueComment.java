package com.codereview.git;

import java.time.Instant;

/**
 * PR 或 Issue 的普通时间线评论。
 */
public record IssueComment(long id, long issueNumber, GitUserRef author, String body, Instant createdAt, Instant updatedAt) {
}
