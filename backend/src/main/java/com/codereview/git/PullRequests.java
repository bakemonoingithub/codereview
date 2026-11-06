package com.codereview.git;

import java.util.List;

/**
 * 宿主 PR 的最小公共字段。列表和详情都不包含描述正文，避免把不可信的长文本带进后续审查。
 */
public final class PullRequests {

    private PullRequests() {
    }

    public record Item(long number, String title, String state, boolean draft, boolean merged,
                       String author, String sourceBranch, String targetBranch, String sourceSha,
                       String targetSha, String sourceRepository, String targetRepository,
                       String url, String updatedAt) {
    }

    public record Page(List<Item> items, boolean hasMore) {
        public Page {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }
}
