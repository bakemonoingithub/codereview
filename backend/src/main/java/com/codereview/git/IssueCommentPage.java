package com.codereview.git;

import java.util.List;

/**
 * PR 或 Issue 普通时间线评论的分页结果。
 */
public record IssueCommentPage(List<IssueComment> items, boolean hasMore, int page, int perPage) {
}
