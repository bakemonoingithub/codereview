package com.codereview.chunk;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 分块器（混合策略）：默认「单文件 = 一个单元」；当文件超过 maxChars 时按类/方法边界切分，
 * 切分失败或无方法时按行硬切兜底。
 */
public final class Chunker {

    private Chunker() {
    }

    public static List<ReviewUnit> chunk(String path, String code, int maxChars) {
        if (code == null || code.isBlank()) {
            return List.of();
        }
        if (code.length() <= maxChars) {
            return List.of(unit(path, "file", fileName(path), 1, countLines(code), code));
        }
        List<ReviewUnit> structured = chunkByStructure(path, code, maxChars);
        if (structured != null && !structured.isEmpty()) {
            return structured;
        }
        return chunkByLines(path, code, maxChars, "file", fileName(path), 1);
    }

    /** 按方法/构造器边界切分；解析失败或无方法时返回 null（交给行切兜底）。 */
    private static List<ReviewUnit> chunkByStructure(String path, String code, int maxChars) {
        CompilationUnit cu;
        try {
            cu = StaticJavaParser.parse(code);
        } catch (Exception e) {
            return null;
        }
        String[] lines = code.split("\n", -1);
        List<Member> members = new ArrayList<>();
        for (MethodDeclaration md : cu.findAll(MethodDeclaration.class)) {
            members.add(new Member(md, md.getNameAsString()));
        }
        for (ConstructorDeclaration cd : cu.findAll(ConstructorDeclaration.class)) {
            members.add(new Member(cd, cd.getNameAsString() + ".<init>"));
        }
        if (members.isEmpty()) {
            return null;
        }
        members.sort(Comparator.comparingInt(Member::beginLine));
        List<ReviewUnit> units = new ArrayList<>();
        for (Member m : members) {
            if (m.beginLine() < 1 || m.endLine() < m.beginLine() || m.endLine() > lines.length) {
                continue;
            }
            String slice = String.join("\n", Arrays.copyOfRange(lines, m.beginLine() - 1, m.endLine()));
            if (slice.length() > maxChars) {
                // 单个方法仍超阈值 → 该方法内部按行硬切（行号相对该方法起点）
                units.addAll(chunkByLines(path, slice, maxChars, "method", m.name(), m.beginLine()));
            } else {
                units.add(unit(path, "method", m.name(), m.beginLine(), m.endLine(), slice));
            }
        }
        return units.isEmpty() ? null : units;
    }

    /** 按行硬切：lineOffset 为「slice 第 0 行」对应的绝对行号（文件整体为 1）。 */
    private static List<ReviewUnit> chunkByLines(String path, String code, int maxChars,
                                                 String kind, String name, int lineOffset) {
        String[] lines = code.split("\n", -1);
        List<ReviewUnit> units = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        int start = 0;
        for (int i = 0; i < lines.length; i++) {
            String next = lines[i];
            if (sb.length() > 0 && sb.length() + 1 + next.length() > maxChars) {
                units.add(unit(path, kind, name, start + lineOffset, i - 1 + lineOffset, sb.toString()));
                sb.setLength(0);
                start = i;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(next);
        }
        if (sb.length() > 0) {
            units.add(unit(path, kind, name, start + lineOffset, lines.length - 1 + lineOffset, sb.toString()));
        }
        return units;
    }

    private static ReviewUnit unit(String path, String kind, String name, int startLine, int endLine, String code) {
        return new ReviewUnit(path, kind, name, startLine, endLine, code);
    }

    private static String fileName(String path) {
        int idx = path.lastIndexOf('/');
        return idx >= 0 ? path.substring(idx + 1) : path;
    }

    private static int countLines(String code) {
        if (code.isEmpty()) {
            return 1;
        }
        int n = 1;
        for (int i = 0; i < code.length(); i++) {
            if (code.charAt(i) == '\n') {
                n++;
            }
        }
        return n;
    }

    /** 方法/构造器节点包装：取其源码行范围与名称。 */
    private static final class Member {
        private final Node node;
        private final String name;

        Member(Node node, String name) {
            this.node = node;
            this.name = name;
        }

        int beginLine() {
            return node.getBegin().map(p -> p.line).orElse(-1);
        }

        int endLine() {
            return node.getEnd().map(p -> p.line).orElse(-1);
        }

        String name() {
            return name;
        }
    }
}
