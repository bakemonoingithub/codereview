package com.codereview.git;

import java.util.List;

/**
 * 提交列表分页结果。
 * <p>
 * 刻意**不返回 total**：GitHub 的 commits 接口不提供总数，硬包成
 * {@code {records,total,current,size}} 会得到一个不可信的数字。
 * 前端据 {@code hasMore} 决定是否继续滚动加载。
 */
public record CommitPage(List<CommitInfo> commits, boolean hasMore) {
}
