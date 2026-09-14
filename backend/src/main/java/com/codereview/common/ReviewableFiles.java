package com.codereview.common;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 可审查文件的**唯一判定点**：扩展名白名单。
 *
 * <p>为什么用白名单而不是黑名单：黑名单永远补不全。此前前端在
 * {@code changedFiles.ts} 里维护了一份二进制后缀表，漏掉了
 * {@code .exe/.dll/.so/.bin/.7z} 等一大批；而且判定分散在前端两处，早晚漂移。
 * 现在判定只在这里，前端的两个视图与后端编排层都读同一份结论。
 *
 * <p>为什么需要它：分析器对文件内容是"来者不拒"的 —— {@code llm-review} 与
 * {@code diff-review} 会把拉到的内容整段交给大模型，二进制文件进去就是一段乱码或 base64，
 * 既浪费额度又产出噪声结论；{@code coupling/design-pattern} 则靠 JavaParser 解析失败
 * 静默丢弃，属于隐式行为。这里把"该不该审"显式化。
 *
 * <p>无扩展名的文件（{@code Makefile}、{@code Dockerfile}、{@code LICENSE}）**不在**名单内，
 * 按不可审查处理 —— 这是刻意的口径，避免"看似文本就放行"。
 */
public final class ReviewableFiles {

    /**
     * 允许交给分析器的扩展名（小写、不含点）。
     *
     * <p>只收"确定是文本、且模型能读懂"的类型。像 {@code .csv}/{@code .log} 这类
     * 大概率是**数据而非代码**的也在名单里，是为了不误伤构建产物以外的文本；
     * 但二进制与可执行文件一律排除。
     */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            // 代码
            "java", "kt", "kts", "scala", "js", "ts", "jsx", "tsx", "vue", "py", "go", "cs",
            "cpp", "c", "h", "rb", "php", "sql", "sh", "bat", "ps1", "r", "swift", "dart",
            "lua", "pl", "m", "mm", "asm", "s",
            // 配置
            "xml", "yml", "yaml", "properties", "json", "toml", "ini", "conf", "gradle", "env",
            // 标记 / 文档
            "md", "txt", "html", "htm", "css", "less", "scss", "sass", "styl", "jsp", "ftl",
            "rst", "adoc", "csv", "log");

    private ReviewableFiles() {
    }

    /** 路径是否落在白名单内。只看扩展名，大小写不敏感。 */
    public static boolean isReviewable(String path) {
        return ALLOWED_EXTENSIONS.contains(extensionOf(path));
    }

    /** 取小写扩展名（不含点）；无扩展名时返回空串。 */
    public static String extensionOf(String path) {
        if (path == null) {
            return "";
        }
        String name = path.substring(path.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        // dot > 0 而非 >= 0：点号开头的文件（.gitignore）不算有扩展名
        return dot > 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    /** 过滤出可审查的路径，保持原顺序。 */
    public static List<String> reviewableOnly(List<String> paths) {
        return paths == null ? List.of() : paths.stream().filter(ReviewableFiles::isReviewable).toList();
    }
}
