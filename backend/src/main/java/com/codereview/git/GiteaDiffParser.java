package com.codereview.git;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Gitea 的 unified diff 文本切分（纯函数，便于单测）。
 *
 * <p>为什么需要它：Gitea 1.16.1 <b>没有任何接口以 JSON 形式返回逐文件 patch</b>
 * （{@code /git/commits/{sha}} 的 {@code files[]} 只有 {@code filename}），
 * 唯一来源是 {@code GET /repos/{o}/{r}/git/commits/{sha}.diff} 返回的原始文本。
 * 本类把这块文本切成与 GitHub {@code files[].patch} <b>同口径</b>的 {@link ChangedFile}：
 * <ul>
 *   <li>{@code patch} 从第一个 {@code @@} 开始（不含 {@code diff --git} / {@code index} /
 *       {@code ---}/{@code +++} 头），与 GitHub 的 patch 字段一致 —— 前端与审查链路按这个口径解析；</li>
 *   <li>二进制（{@code Binary files ... differ}，无 hunk）→ {@code patch} 为空；</li>
 *   <li>{@code status} 由 {@code new file mode}/{@code deleted file mode}/{@code rename from} 推导；</li>
 *   <li>{@code additions}/{@code deletions} 自己数 {@code +/-} 行（Gitea 不提供统计，
 *       而前端在展示这两个数字）。</li>
 * </ul>
 */
final class GiteaDiffParser {

    private GiteaDiffParser() {
    }

    /** 解析整段 diff 文本；结果按路径稳定排序（宿主给出的顺序不稳定）。 */
    static List<ChangedFile> parse(String diffText) {
        List<ChangedFile> files = new ArrayList<>();
        if (diffText == null || diffText.isBlank()) {
            return files;
        }
        String[] lines = diffText.split("\n", -1);
        List<String> block = new ArrayList<>();
        for (String line : lines) {
            if (line.startsWith("diff --git ")) {
                addBlock(files, block);
                block = new ArrayList<>();
            }
            if (!block.isEmpty() || line.startsWith("diff --git ")) {
                block.add(line);
            }
        }
        addBlock(files, block);
        files.sort(Comparator.comparing(ChangedFile::path));
        return files;
    }

    private static void addBlock(List<ChangedFile> files, List<String> block) {
        if (block.isEmpty()) {
            return;
        }
        String newFileMode = null;
        String deletedFileMode = null;
        String renameFrom = null;
        String renameTo = null;
        String oldPath = null;
        String newPath = null;
        String binaryOld = null;
        String binaryNew = null;
        int additions = 0;
        int deletions = 0;
        int firstHunk = -1;

        for (int i = 0; i < block.size(); i++) {
            String line = block.get(i);
            if (line.startsWith("new file mode")) {
                newFileMode = line;
            } else if (line.startsWith("deleted file mode")) {
                deletedFileMode = line;
            } else if (line.startsWith("rename from ")) {
                renameFrom = unquote(line.substring("rename from ".length()));
            } else if (line.startsWith("rename to ")) {
                renameTo = unquote(line.substring("rename to ".length()));
            } else if (line.startsWith("--- ")) {
                oldPath = diffPath(line.substring(4));
            } else if (line.startsWith("+++ ")) {
                newPath = diffPath(line.substring(4));
            } else if (line.startsWith("Binary files ") && line.endsWith(" differ")) {
                String[] parts = line.substring("Binary files ".length(), line.length() - " differ".length())
                        .split(" and ");
                if (parts.length == 2) {
                    binaryOld = diffPath(parts[0].trim());
                    binaryNew = diffPath(parts[1].trim());
                }
            } else if (line.startsWith("@@") && firstHunk < 0) {
                firstHunk = i;
            }
        }

        // 数 +/- 行：只在 hunk 区间内数，且排除 ---/+++ 文件头
        if (firstHunk >= 0) {
            for (int i = firstHunk; i < block.size(); i++) {
                String line = block.get(i);
                if (line.startsWith("@@")) {
                    continue;
                }
                if (line.startsWith("+")) {
                    additions++;
                } else if (line.startsWith("-")) {
                    deletions++;
                } else if (line.startsWith("diff --git ")) {
                    break;
                }
            }
        }

        String path = newPath != null ? newPath : (binaryNew != null ? binaryNew : renameTo);
        String previousPath = null;
        if (newFileMode != null) {
            path = newPath;
        } else if (deletedFileMode != null) {
            path = oldPath != null ? oldPath : binaryOld;
        } else if (renameFrom != null) {
            previousPath = renameFrom;
        } else if (binaryOld != null && binaryNew != null && !binaryOld.equals(binaryNew)) {
            previousPath = binaryOld;
        }
        if (path == null || path.isBlank()) {
            path = renameTo;
        }
        if (path == null || path.isBlank()) {
            return;
        }

        String status;
        if (newFileMode != null) {
            status = ChangedFile.ADDED;
        } else if (deletedFileMode != null) {
            status = ChangedFile.REMOVED;
        } else if (renameFrom != null || (previousPath != null && !previousPath.isBlank())) {
            status = ChangedFile.RENAMED;
        } else {
            status = ChangedFile.MODIFIED;
        }

        String patch = null;
        if (firstHunk >= 0) {
            StringBuilder sb = new StringBuilder();
            for (int i = firstHunk; i < block.size(); i++) {
                String line = block.get(i);
                if (line.startsWith("diff --git ")) {
                    break;
                }
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(line);
            }
            patch = sb.toString();
        }

        Integer add = firstHunk >= 0 ? additions : null;
        Integer del = firstHunk >= 0 ? deletions : null;
        Integer changes = (add == null || del == null) ? null : add + del;
        files.add(new ChangedFile(path, previousPath, status, add, del, changes, patch));
    }

    /** 去掉 diff 里的 {@code a/}、{@code b/} 前缀；{@code /dev/null} 视为"无该侧"。 */
    private static String diffPath(String raw) {
        String p = unquote(raw.trim());
        if (p.isEmpty() || "/dev/null".equals(p)) {
            return null;
        }
        if (p.startsWith("a/") || p.startsWith("b/")) {
            p = p.substring(2);
        }
        return p;
    }

    /** git 对含空格/特殊字符的路径会加引号并转义，这里做最小还原。 */
    private static String unquote(String raw) {
        String s = raw.trim();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length() - 1);
        }
        return s.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\t", "\t");
    }
}
