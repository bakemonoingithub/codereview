package com.codereview.agent.specialist.material;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Java 类型写法的轻量解析：擦除泛型、拆参数、识别数组/通配符/基本类型。
 *
 * <p>不引入完整的类型系统；只服务于“在同一仓库里找候选声明”和“耦合边去重”。
 * 泛型擦除后同名类型可能合并，因此解析器只把确定匹配当作确定关系。
 */
public final class TypeNames {

    private static final Set<String> PRIMITIVES = Set.of(
            "boolean", "byte", "char", "short", "int", "long", "float", "double", "void");

    private static final Set<String> JAVA_LANG = Set.of(
            "Object", "String", "CharSequence", "Number", "Boolean", "Byte", "Character",
            "Short", "Integer", "Long", "Float", "Double", "Void", "Class", "Enum",
            "Throwable", "Exception", "RuntimeException", "Error", "Iterable", "Comparable",
            "Runnable", "Thread", "StringBuilder", "StringBuffer", "Math", "System");

    private TypeNames() {
    }

    public record TypeUse(String base, List<String> arguments, int arrayDepth) {

        public TypeUse {
            arguments = arguments == null ? List.of() : List.copyOf(arguments);
        }

        public boolean primitive() {
            return PRIMITIVES.contains(base);
        }

        public boolean jdkBasic() {
            return primitive() || JAVA_LANG.contains(base);
        }

        public String erasure() {
            return base;
        }
    }

    /** 解析一个类型写法，去掉注解、通配符和数组后缀。 */
    public static TypeUse parse(String raw) {
        if (raw == null) {
            return new TypeUse("", List.of(), 0);
        }
        String text = raw.trim();
        text = stripAnnotations(text);
        int arrayDepth = 0;
        while (text.endsWith("[]") || text.endsWith("...")) {
            arrayDepth++;
            text = text.endsWith("[]")
                    ? text.substring(0, text.length() - 2).trim()
                    : text.substring(0, text.length() - 3).trim();
        }
        if (text.startsWith("? extends ")) {
            text = text.substring("? extends ".length()).trim();
        } else if (text.startsWith("? super ")) {
            text = text.substring("? super ".length()).trim();
        } else if (text.equals("?")) {
            return new TypeUse("?", List.of(), arrayDepth);
        }
        int generic = text.indexOf('<');
        if (generic < 0) {
            return new TypeUse(text, List.of(), arrayDepth);
        }
        String base = text.substring(0, generic).trim();
        int close = matchingClose(text, generic);
        if (close < 0) {
            return new TypeUse(text, List.of(), arrayDepth);
        }
        String inner = text.substring(generic + 1, close);
        List<String> arguments = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == '<') {
                depth++;
            } else if (c == '>') {
                depth--;
            } else if (c == ',' && depth == 0) {
                arguments.add(inner.substring(start, i).trim());
                start = i + 1;
            }
        }
        if (!inner.isBlank()) {
            arguments.add(inner.substring(start).trim());
        }
        return new TypeUse(base, arguments, arrayDepth);
    }

    /** 类型本身及泛型参数中的全部类型名，按出现顺序去重。 */
    public static List<String> allNames(String raw) {
        Set<String> names = new LinkedHashSet<>();
        collect(raw, names, 0);
        names.remove("?");
        names.remove("");
        return List.copyOf(names);
    }

    public static String erasure(String raw) {
        return parse(raw).base();
    }

    public static boolean isPrimitiveOrVoid(String name) {
        return PRIMITIVES.contains(name);
    }

    public static boolean isJdkBasic(String name) {
        return PRIMITIVES.contains(name) || JAVA_LANG.contains(name);
    }

    /** JDK 平台类型不进入仓库内依赖指标；第三方库仍保留为未解析，便于显示覆盖不足。 */
    public static boolean isJdkPlatform(String name) {
        return isJdkBasic(name) || name.startsWith("java.") || name.startsWith("javax.");
    }

    public static boolean isProbablyQualified(String name) {
        if (name == null || !name.contains(".")) {
            return false;
        }
        String first = name.substring(0, name.indexOf('.'));
        return !first.isEmpty() && Character.isLowerCase(first.charAt(0));
    }

    private static void collect(String raw, Set<String> names, int depth) {
        if (raw == null || depth > 8) {
            return;
        }
        TypeUse use = parse(raw);
        if (!use.base().isBlank()) {
            names.add(use.base());
        }
        for (String argument : use.arguments()) {
            collect(argument, names, depth + 1);
        }
    }

    private static String stripAnnotations(String text) {
        String value = text;
        while (value.startsWith("@")) {
            int space = value.indexOf(' ');
            int paren = value.indexOf('(');
            int cut = space < 0 ? value.length() : space;
            if (paren >= 0 && paren < cut) {
                int close = matchingClose(value, paren);
                cut = close < 0 ? value.length() : close + 1;
            }
            value = value.substring(cut).trim();
        }
        return value;
    }

    private static int matchingClose(String text, int open) {
        int depth = 0;
        boolean inString = false;
        boolean inChar = false;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' && !inChar) {
                inString = !inString;
            } else if (c == '\'' && !inString) {
                inChar = !inChar;
            } else if (!inString && !inChar) {
                if (c == '<') {
                    depth++;
                } else if (c == '>') {
                    depth--;
                    if (depth == 0) {
                        return i;
                    }
                }
            }
        }
        return -1;
    }
}
