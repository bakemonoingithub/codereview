package com.codereview.diff;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把「变更文件的 patch」构建成送给 LLM 的审查单元。
 * <p>
 * 主路径：用 JavaParser 解析<b>新侧完整文件</b>，把 hunk 补全到所属方法体，渲染成
 * 「新侧方法体 + 新侧行号 + 变更行内嵌 +/- 标记」。
 * <p>
 * 三条退化策略：
 * <ol>
 *   <li>非 Java / 解析失败 / 找不到所属方法 → <b>回退原始 hunk</b>（并标记未做方法级补全）；</li>
 *   <li>单个方法体超过窗口 → 只保留「变更行 ± N 行」并标注已截断；</li>
 *   <li>patch 为空或不可解析 → 返回空列表，由调用方走<b>全文件兜底</b>。</li>
 * </ol>
 * 组装规则：默认「一个变更文件 = 一个单元」；拼接后超过 {@code maxChars} 时再按块拆分。
 */
public final class DiffUnitBuilder {

    private static final JavaParser PARSER = new JavaParser(
            new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17));

    private final int windowLines;
    private final int maxChars;

    public DiffUnitBuilder(int windowLines, int maxChars) {
        this.windowLines = Math.max(10, windowLines);
        this.maxChars = Math.max(1_000, maxChars);
    }

    /** 渲染好的片段（方法级块或原始 hunk 块）。 */
    private record Block(int startLine, int endLine, String name, String kind, boolean truncated, String text) {
    }

    private record MethodRange(int start, int end, String name) {
    }

    /**
     * 用 patch 构建单元。返回空列表表示"patch 不可用"，调用方应改走 {@link #buildFromFullFile}。
     */
    public List<DiffReviewUnit> buildFromPatch(String path, String changeType, String patch, String newFileContent) {
        List<UnifiedDiffParser.Hunk> hunks = UnifiedDiffParser.parse(patch);
        if (hunks.isEmpty()) {
            return List.of();
        }
        List<Block> blocks = methodLevelBlocks(hunks, newFileContent);
        if (blocks.isEmpty()) {
            // 退化 1：非 Java / 解析失败 / 无归属方法 → 原始 hunk
            blocks = List.of(rawHunkBlock(hunks));
        }
        return assemble(path, changeType, blocks);
    }

    /** 无 patch（二进制 / 过大 / 重命名无内容变化）时的全文件兜底。 */
    public DiffReviewUnit buildFromFullFile(String path, String changeType, String content) {
        String text = numberLines(content);
        int lines = content == null ? 0 : content.split("\n", -1).length;
        return new DiffReviewUnit(path, "full-file", fileName(path), 1, lines, changeType,
                false, "该文件无可用 patch，已退化为审查变更后的完整文件", text);
    }

    // ------------------------------------------------------------------
    // 组装
    // ------------------------------------------------------------------

    private List<DiffReviewUnit> assemble(String path, String changeType, List<Block> blocks) {
        int total = blocks.stream().mapToInt(b -> b.text().length()).sum();
        if (blocks.size() == 1 || total <= maxChars) {
            // 一文件 = 一单元
            StringBuilder sb = new StringBuilder();
            for (Block b : blocks) {
                sb.append(b.text()).append('\n');
            }
            Block first = blocks.get(0);
            Block last = blocks.get(blocks.size() - 1);
            String note = blocks.stream().anyMatch(b -> "diff-hunk".equals(b.kind()))
                    ? "文件内含无法做方法级补全的变更段（已回退原始 hunk）" : null;
            boolean truncated = blocks.stream().anyMatch(Block::truncated);
            return List.of(new DiffReviewUnit(path, dominantKind(blocks), summariseName(blocks),
                    first.startLine(), last.endLine(), changeType, truncated, note, sb.toString().stripTrailing()));
        }
        // 超过阈值 → 按块拆单元
        List<DiffReviewUnit> units = new ArrayList<>();
        for (Block b : blocks) {
            String note = "diff-hunk".equals(b.kind()) ? "该变更段未做方法级补全（回退原始 hunk）" : null;
            units.add(new DiffReviewUnit(path, b.kind(), b.name(), b.startLine(), b.endLine(),
                    changeType, b.truncated(), note, b.text()));
        }
        return units;
    }

    private static String dominantKind(List<Block> blocks) {
        if (blocks.stream().allMatch(b -> "diff-method".equals(b.kind()))) {
            return "diff-method";
        }
        if (blocks.stream().allMatch(b -> "diff-hunk".equals(b.kind()))) {
            return "diff-hunk";
        }
        return "diff-mixed";
    }

    private static String summariseName(List<Block> blocks) {
        List<String> names = new ArrayList<>();
        for (Block b : blocks) {
            if (names.size() >= 3) {
                names.add("…");
                break;
            }
            names.add(b.name());
        }
        return String.join(", ", names);
    }

    // ------------------------------------------------------------------
    // 方法级补全
    // ------------------------------------------------------------------

    private List<Block> methodLevelBlocks(List<UnifiedDiffParser.Hunk> hunks, String newFileContent) {
        if (newFileContent == null || newFileContent.isBlank()) {
            return List.of();
        }
        CompilationUnit cu;
        try {
            var result = PARSER.parse(newFileContent);
            if (!result.isSuccessful() || result.getResult().isEmpty()) {
                return List.of();
            }
            cu = result.getResult().get();
        } catch (Exception e) {
            return List.of();
        }
        List<MethodRange> ranges = methodRanges(cu);
        if (ranges.isEmpty()) {
            return List.of();
        }
        String[] lines = newFileContent.split("\n", -1);

        // 按所属方法分组（保持 hunk 顺序）
        Map<MethodRange, List<UnifiedDiffParser.Hunk>> grouped = new LinkedHashMap<>();
        List<UnifiedDiffParser.Hunk> orphans = new ArrayList<>();
        for (UnifiedDiffParser.Hunk h : hunks) {
            MethodRange owner = ownerOf(ranges, h);
            if (owner == null) {
                orphans.add(h);
            } else {
                grouped.computeIfAbsent(owner, k -> new ArrayList<>()).add(h);
            }
        }

        List<Block> blocks = new ArrayList<>();
        for (Map.Entry<MethodRange, List<UnifiedDiffParser.Hunk>> e : grouped.entrySet()) {
            blocks.add(renderMethodBlock(lines, e.getKey(), e.getValue()));
        }
        if (!orphans.isEmpty()) {
            blocks.add(rawHunkBlock(orphans));
        }
        blocks.sort(Comparator.comparingInt(Block::startLine));
        return blocks;
    }

    /**
     * 找出 hunk 所属的方法：取<b>首个落在某方法内的新侧行</b>——hunk 往往同时包含类声明等
     * 方法外的上下文行，用"首行"定位会落空。纯删除 hunk 新侧没有对应行，退化为按 newStart 就近归属。
     */
    private static MethodRange ownerOf(List<MethodRange> ranges, UnifiedDiffParser.Hunk hunk) {
        for (int line : hunk.newSideLines()) {
            MethodRange owner = enclosing(ranges, line);
            if (owner != null) {
                return owner;
            }
        }
        return enclosing(ranges, Math.max(1, hunk.newStart()));
    }

    private Block renderMethodBlock(String[] lines, MethodRange range, List<UnifiedDiffParser.Hunk> hunks) {
        int methodStart = Math.max(1, range.start());
        int methodEnd = Math.min(lines.length, range.end());

        // 变更行（新增行）与"删除行挂靠位置"
        Set<Integer> added = new LinkedHashSet<>();
        Set<Integer> touched = new LinkedHashSet<>();
        Map<Integer, List<String>> removedAfter = new HashMap<>();
        for (UnifiedDiffParser.Hunk h : hunks) {
            touched.addAll(h.newSideLines());
            added.addAll(h.addedNewLines());
            int lastNew = h.newStart() - 1;
            for (UnifiedDiffParser.Line l : h.lines()) {
                if (l.type() == UnifiedDiffParser.LineType.REMOVED) {
                    removedAfter.computeIfAbsent(lastNew, k -> new ArrayList<>()).add(l.text());
                } else if (l.newLine() != null) {
                    lastNew = l.newLine();
                }
            }
        }

        // 退化 2：方法体超过窗口 → 只保留「变更行 ± N 行」并标注截断
        int from = methodStart;
        int to = methodEnd;
        boolean truncated = false;
        if (methodEnd - methodStart + 1 > windowLines) {
            int lo = touched.isEmpty() ? methodStart : touched.stream().mapToInt(Integer::intValue).min().orElse(methodStart);
            int hi = touched.isEmpty() ? methodEnd : touched.stream().mapToInt(Integer::intValue).max().orElse(methodEnd);
            from = Math.max(methodStart, lo - windowLines);
            to = Math.min(methodEnd, hi + windowLines);
            truncated = true;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("==== 方法: ").append(range.name())
                .append("（新侧 ").append(methodStart).append('-').append(methodEnd).append(" 行");
        if (truncated) {
            sb.append("，仅展示变更行 ±").append(windowLines).append(" 行，共 ").append(from).append('-').append(to);
        }
        sb.append("）====\n");

        for (int ln = from; ln <= to; ln++) {
            for (String removed : removedAfter.getOrDefault(ln - 1, List.of())) {
                sb.append(String.format(" %5s | %s%n", "-", removed));
            }
            String prefix = added.contains(ln) ? "+" : " ";
            sb.append(String.format("%s%5d | %s%n", prefix, ln, lines[ln - 1]));
        }
        // hunk 末尾的删除行（挂靠在方法末行之后）
        for (String removed : removedAfter.getOrDefault(to, List.of())) {
            sb.append(String.format(" %5s | %s%n", "-", removed));
        }
        return new Block(methodStart, methodEnd, range.name(), "diff-method", truncated, sb.toString().stripTrailing());
    }

    // ------------------------------------------------------------------
    // 原始 hunk 退化
    // ------------------------------------------------------------------

    private static Block rawHunkBlock(List<UnifiedDiffParser.Hunk> hunks) {
        StringBuilder sb = new StringBuilder();
        int start = Integer.MAX_VALUE;
        int end = 0;
        for (UnifiedDiffParser.Hunk h : hunks) {
            start = Math.min(start, Math.max(1, h.newStart()));
            end = Math.max(end, h.newEndLine());
            sb.append("==== ").append(h.header().trim()).append(" ====\n");
            for (UnifiedDiffParser.Line l : h.lines()) {
                String marker = switch (l.type()) {
                    case ADDED -> "+";
                    case REMOVED -> "-";
                    case CONTEXT -> " ";
                };
                sb.append(marker).append(l.text()).append('\n');
            }
        }
        return new Block(start == Integer.MAX_VALUE ? 1 : start, Math.max(end, 1),
                "原始 hunk", "diff-hunk", false, sb.toString().stripTrailing());
    }

    // ------------------------------------------------------------------
    // JavaParser 辅助
    // ------------------------------------------------------------------

    private static List<MethodRange> methodRanges(CompilationUnit cu) {
        List<MethodRange> ranges = new ArrayList<>();
        for (MethodDeclaration m : cu.findAll(MethodDeclaration.class)) {
            m.getRange().ifPresent(r -> ranges.add(new MethodRange(r.begin.line, r.end.line, m.getNameAsString())));
        }
        for (ConstructorDeclaration c : cu.findAll(ConstructorDeclaration.class)) {
            c.getRange().ifPresent(r -> ranges.add(new MethodRange(r.begin.line, r.end.line, c.getNameAsString())));
        }
        ranges.sort(Comparator.comparingInt(MethodRange::start));
        return ranges;
    }

    /** 最内层包含该行的方法。 */
    private static MethodRange enclosing(List<MethodRange> ranges, int line) {
        MethodRange best = null;
        for (MethodRange r : ranges) {
            if (line >= r.start() && line <= r.end()) {
                if (best == null || (r.end() - r.start()) < (best.end() - best.start())) {
                    best = r;
                }
            }
        }
        return best;
    }

    private static String numberLines(String content) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        String[] lines = content.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            sb.append(String.format("%5d | %s%n", i + 1, lines[i]));
        }
        return sb.toString().stripTrailing();
    }

    private static String fileName(String path) {
        int idx = path.lastIndexOf('/');
        return idx >= 0 ? path.substring(idx + 1) : path;
    }
}
