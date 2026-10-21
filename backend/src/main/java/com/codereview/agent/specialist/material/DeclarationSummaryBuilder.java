package com.codereview.agent.specialist.material;

import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * 把祖先类型整理成“声明摘要”材料：保留类型头、字段、方法签名、注释和原始行号，
 * 用占位行替换方法体/初始化块，占位行数与原文一致，因此模型引用的行号仍能回读原文。
 *
 * <p>摘要不是原文，{@code completeness} 标为 {@code DECLARATION_SUMMARY}；
 * 阅读界面不能把它当作完整源码，扩展全文也不改变实际发送内容。
 */
@Component
public class DeclarationSummaryBuilder {

    public Summary build(String content, JavaSourceStructure.TypeDecl type) {
        String[] all = content.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        int start = includeLeadingComment(all, type.startLine());
        int end = Math.min(type.endLine(), all.length);
        if (end < start) {
            end = start;
        }
        String[] lines = Arrays.copyOfRange(all, start - 1, end);
        int omitted = 0;
        List<JavaSourceStructure.MethodDecl> methods = type.methods();
        for (JavaSourceStructure.MethodDecl method : methods) {
            if (!method.hasBody()) {
                continue;
            }
            omitted += redact(lines, start, method.bodyStartLine(), method.bodyEndLine(),
                    "// " + method.signature() + " {", "}");
        }
        for (JavaSourceStructure.InitializerDecl initializer : type.initializers()) {
            omitted += redact(lines, start, initializer.startLine(), initializer.endLine(),
                    initializer.isStatic() ? "// static 初始化块 {" : "// 实例初始化块 {", "}");
        }
        for (JavaSourceStructure.MemberRange member : type.memberRanges()) {
            if ("type".equals(member.kind())) {
                omitted += redactNested(lines, start, member.startLine(), member.endLine());
            }
        }
        String text = String.join("\n", lines);
        if (!text.endsWith("\n")) {
            text += "\n";
        }
        return new Summary(text, start, end, omitted);
    }

    /**
     * 用同样行数的注释替换方法体，保持原始行号不变。返回被替换的正文行数。
     */
    private static int redact(String[] lines, int materialStart, int bodyStart, int bodyEnd,
                              String firstLine, String closing) {
        int from = Math.max(0, bodyStart - materialStart);
        int to = Math.min(lines.length - 1, bodyEnd - materialStart);
        if (from > to) {
            return 0;
        }
        String indent = leadingWhitespace(lines[from]);
        int replaced = to - from + 1;
        if (replaced == 1) {
            lines[from] = indent + firstLine + " ... " + closing;
            return 1;
        }
        lines[from] = indent + firstLine;
        for (int i = from + 1; i < to; i++) {
            lines[i] = indent + "// [实现省略]";
        }
        lines[to] = indent + closing;
        return replaced;
    }

    private static int redactNested(String[] lines, int materialStart, int nestedStart,
                                    int nestedEnd) {
        int from = Math.max(0, nestedStart - materialStart);
        int to = Math.min(lines.length - 1, nestedEnd - materialStart);
        if (to - from < 2) {
            return 0;
        }
        String indent = leadingWhitespace(lines[from]);
        for (int i = from + 1; i < to; i++) {
            lines[i] = indent + "    // [嵌套类型实现省略]";
        }
        return to - from - 1;
    }

    private static int includeLeadingComment(String[] lines, int typeStart) {
        int start = typeStart;
        while (start > 1) {
            String previous = lines[start - 2].strip();
            boolean comment = previous.startsWith("//") || previous.startsWith("/*")
                    || previous.startsWith("*") || previous.endsWith("*/");
            if (!comment) {
                break;
            }
            start--;
        }
        return start;
    }

    private static String leadingWhitespace(String line) {
        int index = 0;
        while (index < line.length() && Character.isWhitespace(line.charAt(index))) {
            index++;
        }
        return line.substring(0, index);
    }

    public record Summary(String content, int startLine, int endLine, int omittedBodyLines) {
    }
}
