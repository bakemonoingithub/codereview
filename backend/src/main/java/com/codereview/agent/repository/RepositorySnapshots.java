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
     * 差异的一侧。
     *
     * <p>{@code status} 只有三种：PRESENT、MISSING（确实不存在）、UNREADABLE（存在但读不到）。
     * UNREADABLE 的 {@code reason} 说明原因（BINARY / TOO_LARGE / READ_FAILED），
     * 不再把所有读取失败都当成二进制。
     */
    public record Side(String content, String status, String reason) {

        public static final String PRESENT = "PRESENT";
        public static final String MISSING = "MISSING";
        public static final String UNREADABLE = "UNREADABLE";

        public static Side present(String content) {
            return new Side(content, PRESENT, null);
        }

        public static Side missing(String reason) {
            return new Side(null, MISSING, reason);
        }

        public static Side unreadable(String reason) {
            return new Side(null, UNREADABLE, reason);
        }

        public boolean present() {
            return PRESENT.equals(status);
        }

        public boolean binary() {
            return "BINARY".equals(reason);
        }
    }

    /**
     * 提交差异的两侧。
     *
     * <p>新增文件没有 before、删除文件没有 after，根提交没有父版本；这些都体现在对应
     * {@link Side} 的状态里，不拿另一侧或当前分支顶替。{@code path} 始终是变更项的规范新路径，
     * 重命名前的路径放在 {@code oldPath}。
     */
    public record DiffSides(String headSha, String baseSha, String path, String oldPath, String status,
                            String patch, Side before, Side after) {

        public boolean binary() {
            return before.binary() || after.binary();
        }
    }

    public record PathList(List<PathEntry> entries, String sha, boolean truncated) {
    }
}
