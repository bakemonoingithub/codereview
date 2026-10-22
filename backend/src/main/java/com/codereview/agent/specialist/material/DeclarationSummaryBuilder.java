package com.codereview.agent.specialist.material;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** 声明摘要仅省略 AST 实现范围内的文本；保留范围外声明、注释与原始行号。 */
@Component
public class DeclarationSummaryBuilder {

    public Summary build(String content, JavaSourceStructure.TypeDecl type) {
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
        String[] all = normalized.split("\n", -1);
        int start = includeLeadingComment(all, type.startLine());
        int end = Math.min(type.endLine(), all.length);
        int[] offsets = new int[all.length];
        for (int i = 1; i < all.length; i++) offsets[i] = offsets[i - 1] + all[i - 1].length() + 1;

        List<Span> spans = type.implementationRanges().stream().map(range -> new Span(
                offsets[range.startLine() - 1] + range.startColumn(),
                offsets[range.endLine() - 1] + range.endColumn() - 1))
                .sorted(Comparator.comparingInt(Span::from).thenComparing(
                        Comparator.comparingInt(Span::to).reversed())).toList();
        List<Span> outermost = new ArrayList<>();
        for (Span span : spans) {
            if (span.from() > span.to()) throw new IllegalArgumentException("实现范围非法");
            // 外层方法体已包含局部/匿名类型的实现，不重复替换嵌套区间。
            if (outermost.isEmpty() || span.from() > outermost.get(outermost.size() - 1).to()) {
                outermost.add(span);
            }
        }
        StringBuilder redacted = new StringBuilder(normalized);
        int omitted = 0;
        for (int i = outermost.size() - 1; i >= 0; i--) {
            Span span = outermost.get(i);
            String body = normalized.substring(span.from(), span.to());
            String[] lines = body.split("\n", -1);
            omitted += lines.length;
            if (lines.length == 1) {
                lines[0] = " /* [实现省略] */ ";
            } else {
                lines[0] = " /* [实现省略] */";
                for (int line = 1; line < lines.length - 1; line++) {
                    lines[line] = leadingWhitespace(lines[line]) + "// [实现省略]";
                }
                lines[lines.length - 1] = leadingWhitespace(lines[lines.length - 1]);
            }
            redacted.replace(span.from(), span.to(), String.join("\n", lines));
        }
        String[] result = redacted.toString().split("\n", -1);
        String text = String.join("\n", Arrays.copyOfRange(result, start - 1, end));
        if (!text.endsWith("\n")) text += "\n";
        return new Summary(text, start, end, omitted);
    }

    private static int includeLeadingComment(String[] lines, int typeStart) {
        int start = typeStart;
        while (start > 1) {
            String previous = lines[start - 2].strip();
            boolean comment = previous.startsWith("//") || previous.startsWith("/*")
                    || previous.startsWith("*") || previous.endsWith("*/");
            if (!comment) break;
            start--;
        }
        return start;
    }

    private static String leadingWhitespace(String line) {
        int index = 0;
        while (index < line.length() && Character.isWhitespace(line.charAt(index))) index++;
        return line.substring(0, index);
    }

    private record Span(int from, int to) {
    }

    public record Summary(String content, int startLine, int endLine, int omittedBodyLines) {
    }
}
