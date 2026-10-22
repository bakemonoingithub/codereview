package com.codereview.git;

import java.util.List;

/**
 * 文件树及其完整性。
 *
 * <p>{@code truncated} 为 true 表示宿主明确告知"还有条目没有返回"（如 GitHub 的
 * {@code truncated} 字段、Gitea 翻页达到上限）。调用方不得把这种结果当作完整清单。
 */
public record GitTree(List<GitTreeEntry> entries, boolean truncated) {

    public static GitTree complete(List<GitTreeEntry> entries) {
        return new GitTree(entries, false);
    }
}
