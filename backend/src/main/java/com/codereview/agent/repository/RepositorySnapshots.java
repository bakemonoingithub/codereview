package com.codereview.agent.repository;

import java.util.List;

/** 仓库读取的返回值；{@code side} 与 {@code sha} 是阅读和报告引用的身份，不可互换。 */
public final class RepositorySnapshots {

    private RepositorySnapshots() {
    }

    public record PathEntry(String path, String type, boolean reviewable) {
    }

    /** 固定版本文件：正文、哈希与体积一起返回，调用方不自行猜测完整性。 */
    public record FileSnapshot(String sha, String path, String content, String contentHash,
                               int byteSize, int lineCount) {
    }

    public record RangeSnapshot(String sha, String path, int startLine, int endLine,
                                String content, boolean hasMore) {
    }

    /**
     * 提交差异的两侧。
     *
     * <p>{@code before}/{@code after} 为 null 表示该侧确实不存在（新增/删除）或无法取得；
     * 用 {@code beforeMissing}/{@code afterMissing} 区分，不拿另一侧或当前分支顶替。
     */
    public record DiffSides(String headSha, String baseSha, String path, String oldPath, String status,
                            String before, String after, String patch,
                            boolean beforeMissing, boolean afterMissing, boolean binary, String note) {
    }

    public record PathList(List<PathEntry> entries, String sha, boolean truncated) {
    }
}
