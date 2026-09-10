package com.codereview.git;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Git 宿主响应解析（纯函数，便于单测）。
 * <p>
 * 统一在此处消化 GitHub 与 Gitea 的差异：
 * <ul>
 *   <li>{@code patch} 可能整体缺失（Gitea 的 JSON 从不返回 patch）；</li>
 *   <li>重命名信息可能缺失（Gitea 不检测重命名）；</li>
 *   <li>{@code files[]} 顺序可能不稳定（Gitea 由 Go map 遍历生成）。</li>
 * </ul>
 */
final class GitResponseParser {

    private GitResponseParser() {
    }

    /** Link 头是否含 {@code rel="next"}（GitHub 借此告知还有下一页 / 还有更多文件）。 */
    static boolean hasNextPage(String linkHeader) {
        return linkHeader != null && linkHeader.contains("rel=\"next\"");
    }

    /** 解析 files[]：状态归一化、重命名推导、patch 可空、**按路径稳定排序**。 */
    static List<ChangedFile> parseFiles(JsonNode filesNode) {
        List<ChangedFile> files = new ArrayList<>();
        if (filesNode == null || !filesNode.isArray()) {
            return files;
        }
        for (JsonNode n : filesNode) {
            String path = text(n, "filename");
            if (path == null) {
                continue;
            }
            String previousPath = text(n, "previous_filename");
            files.add(new ChangedFile(
                    path,
                    previousPath,
                    normalizeStatus(text(n, "status"), previousPath),
                    intOrNull(n, "additions"),
                    intOrNull(n, "deletions"),
                    intOrNull(n, "changes"),
                    text(n, "patch")));
        }
        files.sort(Comparator.comparing(ChangedFile::path));
        return files;
    }

    /** 解析单提交详情；{@code truncated} 由调用方依 Link 头判定后传入。 */
    static CommitDetail parseCommit(JsonNode root, boolean truncated) {
        List<String> parents = new ArrayList<>();
        for (JsonNode p : root.path("parents")) {
            String sha = text(p, "sha");
            if (sha != null) {
                parents.add(sha);
            }
        }
        JsonNode commit = root.path("commit");
        JsonNode stats = root.path("stats");
        return new CommitDetail(
                text(root, "sha"),
                parents,
                text(commit, "message"),
                text(commit.path("author"), "name"),
                text(commit.path("author"), "date"),
                intOrNull(stats, "additions"),
                intOrNull(stats, "deletions"),
                intOrNull(stats, "total"),
                truncated,
                parseFiles(root.path("files")));
    }

    /** 解析提交列表中的一条。 */
    static CommitInfo parseCommitInfo(JsonNode node) {
        JsonNode commit = node.path("commit");
        return new CommitInfo(
                text(node, "sha"),
                text(commit, "message"),
                text(commit.path("author"), "name"),
                text(commit.path("author"), "date"));
    }

    /** 状态归一化：缺失时按有无原路径推断，保证下游拿到统一的小写枚举。 */
    private static String normalizeStatus(String status, String previousPath) {
        if (status != null && !status.isBlank()) {
            return status.toLowerCase();
        }
        return (previousPath != null && !previousPath.isBlank()) ? ChangedFile.RENAMED : ChangedFile.MODIFIED;
    }

    private static String text(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) {
            return null;
        }
        String s = v.asText();
        return s.isEmpty() ? null : s;
    }

    private static Integer intOrNull(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        JsonNode v = node.path(field);
        return (v.isMissingNode() || v.isNull() || !v.isNumber()) ? null : v.asInt();
    }
}
