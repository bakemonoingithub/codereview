package com.codereview.git;

/**
 * 单个变更文件（按「最小公共能力」设计，屏蔽 GitHub 与 Gitea 的能力差异）。
 * <ul>
 *   <li>{@code patch} 可为空：二进制文件、过大 diff，或宿主根本不返回
 *       （Gitea 的 commit/compare JSON 完全不返回 patch，只能走 {@code .patch}/{@code .diff} 文本接口）；</li>
 *   <li>{@code previousPath} 可为空：仅重命名时有值（GitHub 提供 {@code previous_filename}，
 *       Gitea 用 {@code --no-renames} 不检测重命名，恒为空）；</li>
 *   <li>{@code status}：{@code added} / {@code modified} / {@code removed} / {@code renamed}
 *       （Gitea 只给前三种）。</li>
 * </ul>
 */
public record ChangedFile(
        String path,
        String previousPath,
        String status,
        Integer additions,
        Integer deletions,
        Integer changes,
        String patch) {

    public static final String ADDED = "added";
    public static final String MODIFIED = "modified";
    public static final String REMOVED = "removed";
    public static final String RENAMED = "renamed";

    /** 是否重命名：状态为 renamed，或宿主给了原路径。 */
    public boolean renamed() {
        return RENAMED.equals(status) || (previousPath != null && !previousPath.isBlank());
    }

    public boolean removed() {
        return REMOVED.equals(status);
    }

    public boolean added() {
        return ADDED.equals(status);
    }

    /** 是否带可用 patch（空 patch 视为不可审查，将由上层走"全文件兜底"）。 */
    public boolean hasPatch() {
        return patch != null && !patch.isBlank();
    }

    /** 列表视图：剥离 patch（对应「清单与 patch 分离」，避免大提交首屏卡顿）。 */
    public ChangedFile withoutPatch() {
        if (patch == null) {
            return this;
        }
        return new ChangedFile(path, previousPath, status, additions, deletions, changes, null);
    }
}
