package com.codereview.agent.repository;

import java.util.List;

/** 本系统的 PR 只读视图。字段来自宿主元数据和固定 SHA 比较，不包含凭据或 PR 描述正文。 */
public final class PullRequestViews {

    public static final String CROSS_REPOSITORY = "CROSS_REPOSITORY";
    public static final String PR_NOT_OPEN = "PR_NOT_OPEN";
    public static final String NO_MERGE_BASE = "NO_MERGE_BASE";
    public static final String TRUNCATED = "TRUNCATED";

    private PullRequestViews() {
    }

    public record Item(long number, String title, String state, boolean draft, boolean merged,
                       String author, String sourceBranch, String targetBranch, String sourceHead,
                       String targetHead, String sourceRepository, String targetRepository,
                       boolean sameRepository, String url, String updatedAt, String provider) {
    }

    public record Page(List<Item> items, boolean hasMore) {
        public Page {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    public record Detail(Item pullRequest, String mergeBase, String blocker) {
    }

    public record FileChange(String path, String oldPath, String status, String category, String reason) {

        public boolean text() {
            return "TEXT".equals(category);
        }
    }

    public record Changes(long number, String sourceHead, String targetHead, String mergeBase,
                          String completeness, String blocker, int fileCount, int textFiles,
                          int binaryFiles, int unreadableFiles, List<FileChange> files) {
        public Changes {
            files = files == null ? List.of() : List.copyOf(files);
        }
    }

    /** 创建任务时已经核对过的 PR 版本和完整净变化。 */
    public record Binding(Item pullRequest, String mergeBase, String completeness,
                          List<FileChange> files, int textFiles, int binaryFiles, int unreadableFiles) {
        public Binding {
            files = files == null ? List.of() : List.copyOf(files);
        }
    }
}
