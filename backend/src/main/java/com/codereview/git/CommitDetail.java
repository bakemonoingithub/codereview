package com.codereview.git;

import java.util.List;

/**
 * 单提交详情：一次调用同时解决「比较基线」与「变更内容」两件事。
 * <ul>
 *   <li>{@code parents[0]} 即 base —— 与第一个父提交比较，merge 提交会被标注；</li>
 *   <li>{@code files} 已做稳定排序（Gitea 的 {@code files[]} 顺序不稳定）；</li>
 *   <li>{@code truncated} 表示宿主提示"还有更多文件未返回"（GitHub 通过 {@code Link} 分页头告知）。
 *       <b>注意</b>：GitHub 的 compare 接口最多回 300 个文件且**无任何截断标志**，
 *       本类只对能检测到的情况置位。</li>
 * </ul>
 */
public record CommitDetail(
        String sha,
        List<String> parents,
        String message,
        String author,
        String date,
        Integer additions,
        Integer deletions,
        Integer totalChanges,
        boolean truncated,
        List<ChangedFile> files) {

    /** 是否 merge 提交（多父）。 */
    public boolean merge() {
        return parents != null && parents.size() > 1;
    }

    /** 比较基线：第一父提交；无父提交（仓库首个提交）时为 null。 */
    public String baseSha() {
        return (parents == null || parents.isEmpty()) ? null : parents.get(0);
    }

    public int fileCount() {
        return files == null ? 0 : files.size();
    }

    /** 列表视图：剥离全部 patch（清单与 patch 分离）。 */
    public CommitDetail withoutPatches() {
        List<ChangedFile> stripped = files == null
                ? List.of()
                : files.stream().map(ChangedFile::withoutPatch).toList();
        return new CommitDetail(sha, parents, message, author, date,
                additions, deletions, totalChanges, truncated, stripped);
    }
}
