package com.codereview.agent.specialist.material;

import java.util.List;
import java.util.Optional;

/**
 * 一个 Java 源文件的结构结果。
 *
 * <p>这里保存的是“可核对的声明事实”，不是模型摘要：包、导入、类型、成员位置都来自
 * JavaParser 解析后的 AST。行号一律是原文件行号，后续拆分、材料摘要和位置校验共用同一套行号。
 *
 * <p>解析失败时 {@code parseError} 非空，其余字段保持为空；调用方必须把解析失败与
 * “文件不存在”分开处理，不能当成零依赖或空成功。
 */
public record JavaSourceStructure(
        String path,
        String packageName,
        List<ImportInfo> imports,
        List<TypeDecl> types,
        List<LineRange> coverage,
        List<Integer> uncoveredLines,
        int lineCount,
        String parseError) {

    public JavaSourceStructure {
        imports = imports == null ? List.of() : List.copyOf(imports);
        types = types == null ? List.of() : List.copyOf(types);
        coverage = coverage == null ? List.of() : List.copyOf(coverage);
        uncoveredLines = uncoveredLines == null ? List.of() : List.copyOf(uncoveredLines);
    }

    public boolean parsed() {
        return parseError == null;
    }

    public boolean declares(String simpleName) {
        return types.stream().anyMatch(type -> type.simpleName().equals(simpleName));
    }

    /** 限定引用必须匹配完整身份；不能因末段同名就当作文件内类型。 */
    public boolean declaresReference(String name) {
        if (TypeNames.isProbablyQualified(name)) {
            return type(name).isPresent();
        }
        if (name.contains(".")) {
            String qualified = packageName.isBlank() ? name : packageName + "." + name;
            return type(qualified).isPresent();
        }
        return declares(name);
    }

    public Optional<TypeDecl> type(String qualifiedName) {
        return types.stream().filter(type -> type.qualifiedName().equals(qualifiedName)).findFirst();
    }

    public Optional<TypeDecl> topLevelType() {
        return types.stream().filter(type -> type.outerQualifiedName() == null).findFirst();
    }

    public List<TypeDecl> topLevelTypes() {
        return types.stream().filter(type -> type.outerQualifiedName() == null).toList();
    }

    public record ImportInfo(String name, boolean isStatic, boolean wildcard) {
    }

    /** 源码中写出的类型引用；{@code raw} 可能是 {@code Map<String, Order>} 或 {@code Outer.Inner}。 */
    public record TypeRef(String raw, int line) {
    }

    public record FieldDecl(String type, String name, int line) {
    }

    /** 可调用成员；构造器的 returnType 为空，紧凑构造器的参数由 record 字段承载。 */
    public record MethodDecl(String name, String returnType, List<String> parameterTypes,
                             boolean isStatic, int startLine, int endLine,
                             int bodyStartLine, int bodyEndLine, String signature,
                             List<String> comments) {

        public MethodDecl {
            parameterTypes = parameterTypes == null ? List.of() : List.copyOf(parameterTypes);
            comments = comments == null ? List.of() : List.copyOf(comments);
        }

        public boolean hasBody() {
            return bodyStartLine > 0 && bodyEndLine >= bodyStartLine;
        }
    }

    public record InitializerDecl(int startLine, int endLine, boolean isStatic) {
    }

    public record MemberRange(String kind, String name, int startLine, int endLine) {
    }

    /** 花括号包围的实现范围，列从 1 开始，保留同一行中范围外的声明。 */
    public record BodyRange(int startLine, int startColumn, int endLine, int endColumn) {
    }

    /**
     * 一个类型声明。{@code qualifiedName} 含外部类型前缀，例如 {@code a.b.Outer.Inner}；
     * {@code outerQualifiedName} 为空表示顶层类型。
     */
    public record TypeDecl(String qualifiedName, String simpleName, String kind,
                           String outerQualifiedName, List<String> annotations,
                           List<TypeRef> extendedTypes, List<TypeRef> implementedTypes,
                           List<FieldDecl> fields, List<MethodDecl> methods,
                           List<InitializerDecl> initializers, List<TypeRef> objectCreations,
                           List<MemberRange> memberRanges, List<BodyRange> implementationRanges,
                           List<String> comments,
                           int startLine, int endLine) {

        public TypeDecl {
            annotations = annotations == null ? List.of() : List.copyOf(annotations);
            extendedTypes = extendedTypes == null ? List.of() : List.copyOf(extendedTypes);
            implementedTypes = implementedTypes == null ? List.of() : List.copyOf(implementedTypes);
            fields = fields == null ? List.of() : List.copyOf(fields);
            methods = methods == null ? List.of() : List.copyOf(methods);
            initializers = initializers == null ? List.of() : List.copyOf(initializers);
            objectCreations = objectCreations == null ? List.of() : List.copyOf(objectCreations);
            memberRanges = memberRanges == null ? List.of() : List.copyOf(memberRanges);
            implementationRanges = implementationRanges == null ? List.of() : List.copyOf(implementationRanges);
            comments = comments == null ? List.of() : List.copyOf(comments);
        }
    }

    public record LineRange(int startLine, int endLine) {
        public LineRange {
            if (startLine < 1 || endLine < startLine) {
                throw new IllegalArgumentException("非法行范围：" + startLine + "-" + endLine);
            }
        }
    }
}
