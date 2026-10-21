package com.codereview.agent.specialist.material;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.AnnotationMemberDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.CompactConstructorDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.codereview.agent.specialist.material.JavaSourceStructure.FieldDecl;
import static com.codereview.agent.specialist.material.JavaSourceStructure.BodyRange;
import static com.codereview.agent.specialist.material.JavaSourceStructure.ImportInfo;
import static com.codereview.agent.specialist.material.JavaSourceStructure.InitializerDecl;
import static com.codereview.agent.specialist.material.JavaSourceStructure.LineRange;
import static com.codereview.agent.specialist.material.JavaSourceStructure.MemberRange;
import static com.codereview.agent.specialist.material.JavaSourceStructure.MethodDecl;
import static com.codereview.agent.specialist.material.JavaSourceStructure.TypeDecl;
import static com.codereview.agent.specialist.material.JavaSourceStructure.TypeRef;

/**
 * JavaParser 结构解析。
 *
 * <p>只抽取后续材料准备、声明定位、耦合指标和拆分要用的声明事实：
 * 包、导入、类型、继承、字段、方法签名、初始化块、注释与原始行号。
 * 不复用旧 {@code StructureExtractor} 的“只取顶层类、字段/方法扁平列表”口径，
 * 因为那个口径无法核对嵌套类型和成员覆盖。
 */
@Component
public class JavaStructureParser {

    private static final ParserConfiguration CONFIG = new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17)
            .setTabSize(1)
            .setAttributeComments(true);

    public JavaSourceStructure parse(String path, String content) {
        String text = content == null ? "" : content;
        int lineCount = countLines(text);
        if (text.isBlank()) {
            throw new StructureParseException("Java 文件为空，无法解析结构：" + path);
        }
        ParseResult<CompilationUnit> result = new JavaParser(CONFIG).parse(text);
        if (!result.isSuccessful() || result.getResult().isEmpty()) {
            String problems = result.getProblems().stream()
                    .limit(3)
                    .map(problem -> problem.getMessage())
                    .collect(Collectors.joining("; "));
            throw new StructureParseException("Java 解析失败：" + path
                    + (problems.isBlank() ? "" : "；" + problems));
        }
        CompilationUnit cu = result.getResult().get();
        String packageName = cu.getPackageDeclaration()
                .map(declaration -> declaration.getNameAsString())
                .orElse("");

        List<ImportInfo> imports = new ArrayList<>();
        for (ImportDeclaration declaration : cu.getImports()) {
            imports.add(new ImportInfo(declaration.getNameAsString(),
                    declaration.isStatic(), declaration.isAsterisk()));
        }

        List<TypeDecl> types = new ArrayList<>();
        for (TypeDeclaration<?> topLevel : cu.getTypes()) {
            collectType(topLevel, packageName, null, types);
        }

        List<LineRange> coverage = coverage(cu, types, lineCount);
        List<Integer> uncovered = uncoveredLines(text, coverage, lineCount);
        return new JavaSourceStructure(path, packageName, imports, types, coverage, uncovered,
                lineCount, null);
    }

    private void collectType(TypeDeclaration<?> type, String packageName, String outer,
                             List<TypeDecl> types) {
        String simpleName = type.getNameAsString();
        String qualifiedName = outer == null
                ? (packageName.isBlank() ? simpleName : packageName + "." + simpleName)
                : outer + "." + simpleName;

        List<String> annotations = type.getAnnotations().stream()
                .map(annotation -> annotation.getNameAsString())
                .toList();
        List<TypeRef> extended = new ArrayList<>();
        List<TypeRef> implemented = new ArrayList<>();
        List<FieldDecl> fields = new ArrayList<>();
        List<MethodDecl> methods = new ArrayList<>();
        List<InitializerDecl> initializers = new ArrayList<>();
        List<TypeRef> objectCreations = new ArrayList<>();
        List<MemberRange> members = new ArrayList<>();
        List<String> comments = commentsOf(type);

        String kind;
        if (type instanceof ClassOrInterfaceDeclaration declaration) {
            kind = declaration.isInterface() ? "interface" : "class";
            declaration.getExtendedTypes().forEach(reference ->
                    extended.add(typeRef(reference.asString(), reference)));
            declaration.getImplementedTypes().forEach(reference ->
                    implemented.add(typeRef(reference.asString(), reference)));
        } else if (type instanceof EnumDeclaration declaration) {
            kind = "enum";
            declaration.getImplementedTypes().forEach(reference ->
                    implemented.add(typeRef(reference.asString(), reference)));
            for (EnumConstantDeclaration entry : declaration.getEntries()) {
                members.add(memberRange("enum-constant", entry.getNameAsString(), entry));
            }
        } else if (type instanceof RecordDeclaration declaration) {
            kind = "record";
            declaration.getImplementedTypes().forEach(reference ->
                    implemented.add(typeRef(reference.asString(), reference)));
            for (Parameter parameter : declaration.getParameters()) {
                fields.add(new FieldDecl(parameter.getType().asString(), parameter.getNameAsString(),
                        lineOf(parameter)));
            }
        } else if (type instanceof AnnotationDeclaration) {
            kind = "annotation";
        } else {
            kind = "type";
        }

        for (BodyDeclaration<?> member : type.getMembers()) {
            if (member instanceof FieldDeclaration field) {
                field.getVariables().forEach(variable -> fields.add(new FieldDecl(
                        variable.getType().asString(), variable.getNameAsString(), lineOf(variable))));
                members.add(memberRange("field", fieldName(field), field));
            } else if (member instanceof MethodDeclaration method) {
                methods.add(methodDecl(method));
                members.add(memberRange("method", method.getNameAsString(), method));
            } else if (member instanceof ConstructorDeclaration constructor) {
                List<String> params = constructor.getParameters().stream()
                        .map(parameter -> parameter.getType().asString()).toList();
                methods.add(new MethodDecl(constructor.getNameAsString(), "", params, false,
                        lineOf(constructor), endLineOf(constructor), lineOf(constructor.getBody()),
                        endLineOf(constructor.getBody()), constructor.getNameAsString() + "("
                        + String.join(", ", params) + ")", commentsOf(constructor)));
                members.add(memberRange("constructor", constructor.getNameAsString(), constructor));
            } else if (member instanceof CompactConstructorDeclaration constructor) {
                methods.add(new MethodDecl(constructor.getNameAsString(), "", List.of(), false,
                        lineOf(constructor), endLineOf(constructor), lineOf(constructor.getBody()),
                        endLineOf(constructor.getBody()), constructor.getNameAsString(), commentsOf(constructor)));
                members.add(memberRange("constructor", constructor.getNameAsString(), constructor));
            } else if (member instanceof InitializerDeclaration initializer) {
                initializers.add(new InitializerDecl(lineOf(initializer),
                        endLineOf(initializer), initializer.isStatic()));
                members.add(memberRange("initializer", initializer.isStatic() ? "static" : "instance",
                        initializer));
            } else if (member instanceof TypeDeclaration<?> nested) {
                members.add(memberRange("type", nested.getNameAsString(), nested));
            } else if (member instanceof AnnotationMemberDeclaration annotationMember) {
                members.add(memberRange("annotation-member", annotationMember.getNameAsString(),
                        annotationMember));
            }
        }

        // 只收集本类型直接成员里的 new；嵌套类型由自己的 TypeDecl 收集，避免同一创建被算两次。
        for (BodyDeclaration<?> member : type.getMembers()) {
            if (member instanceof TypeDeclaration<?>) {
                continue;
            }
            for (ObjectCreationExpr creation : member.findAll(ObjectCreationExpr.class)) {
                objectCreations.add(typeRef(creation.getType().asString(), creation.getType()));
            }
        }

        TypeDecl declaration = new TypeDecl(qualifiedName, simpleName, kind, outer, annotations,
                extended, implemented, fields, methods, initializers, dedupeRefs(objectCreations),
                members, implementationRanges(type), comments, lineOf(type), endLineOf(type));
        types.add(declaration);

        for (BodyDeclaration<?> member : type.getMembers()) {
            if (member instanceof TypeDeclaration<?> nested) {
                collectType(nested, packageName, qualifiedName, types);
            }
        }
    }

    private MethodDecl methodDecl(MethodDeclaration method) {
        List<String> params = new ArrayList<>();
        for (Parameter parameter : method.getParameters()) {
            params.add(parameter.getType().asString());
        }
        String signature = (method.isStatic() ? "static " : "")
                + method.getType().asString() + " " + method.getNameAsString()
                + "(" + String.join(", ", params) + ")";
        int bodyStart = 0;
        int bodyEnd = 0;
        if (method.getBody().isPresent()) {
            bodyStart = lineOf(method.getBody().get());
            bodyEnd = endLineOf(method.getBody().get());
        }
        return new MethodDecl(method.getNameAsString(), method.getType().asString(), params,
                method.isStatic(), lineOf(method), endLineOf(method), bodyStart, bodyEnd,
                signature, commentsOf(method));
    }

    private static List<BodyRange> implementationRanges(TypeDeclaration<?> type) {
        List<Node> bodies = new ArrayList<>();
        type.findAll(MethodDeclaration.class).forEach(method -> method.getBody().ifPresent(bodies::add));
        type.findAll(ConstructorDeclaration.class).forEach(constructor -> bodies.add(constructor.getBody()));
        type.findAll(CompactConstructorDeclaration.class).forEach(constructor -> bodies.add(constructor.getBody()));
        type.findAll(InitializerDeclaration.class).forEach(initializer -> bodies.add(initializer.getBody()));
        return bodies.stream().map(body -> body.getRange().orElseThrow())
                .map(range -> new BodyRange(range.begin.line, range.begin.column,
                        range.end.line, range.end.column)).toList();
    }

    private List<LineRange> coverage(CompilationUnit cu, List<TypeDecl> types, int lineCount) {
        List<LineRange> ranges = new ArrayList<>();
        int firstTypeLine = types.stream().mapToInt(TypeDecl::startLine).min().orElse(lineCount + 1);
        if (firstTypeLine > 1) {
            ranges.add(new LineRange(1, Math.min(firstTypeLine - 1, lineCount)));
        }
        for (TypeDecl type : types) {
            ranges.add(new LineRange(type.startLine(), Math.min(type.endLine(), lineCount)));
        }
        Set<LineRange> comments = new LinkedHashSet<>();
        List<Comment> allComments = new ArrayList<>(cu.getAllContainedComments());
        allComments.addAll(cu.getOrphanComments());
        for (Comment comment : allComments) {
            comment.getRange().ifPresent(range ->
                    comments.add(new LineRange(range.begin.line, Math.min(range.end.line, lineCount))));
        }
        ranges.addAll(comments);
        ranges.sort(Comparator.comparingInt(LineRange::startLine));
        return List.copyOf(ranges);
    }

    private List<Integer> uncoveredLines(String content, List<LineRange> coverage, int lineCount) {
        boolean[] covered = new boolean[lineCount + 1];
        for (LineRange range : coverage) {
            for (int line = Math.max(1, range.startLine());
                 line <= Math.min(lineCount, range.endLine()); line++) {
                covered[line] = true;
            }
        }
        String[] lines = content.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        List<Integer> uncovered = new ArrayList<>();
        for (int line = 1; line <= lineCount; line++) {
            if (!covered[line] && !lines[line - 1].isBlank()) {
                uncovered.add(line);
            }
        }
        return List.copyOf(uncovered);
    }

    private static List<TypeRef> dedupeRefs(List<TypeRef> refs) {
        Set<String> seen = new LinkedHashSet<>();
        List<TypeRef> result = new ArrayList<>();
        for (TypeRef ref : refs) {
            if (seen.add(ref.raw() + "@" + ref.line())) {
                result.add(ref);
            }
        }
        return List.copyOf(result);
    }

    private static MemberRange memberRange(String kind, String name, Node node) {
        return new MemberRange(kind, name, lineOf(node), endLineOf(node));
    }

    private static TypeRef typeRef(String raw, Node node) {
        return new TypeRef(raw, lineOf(node));
    }

    private static String fieldName(FieldDeclaration field) {
        return field.getVariables().isEmpty() ? "" : field.getVariable(0).getNameAsString();
    }

    private static List<String> commentsOf(Node node) {
        List<String> comments = new ArrayList<>();
        node.getComment().ifPresent(comment -> comments.add(comment.toString().strip()));
        return comments;
    }

    private static int lineOf(Node node) {
        return node.getRange().map(range -> range.begin.line).orElse(1);
    }

    private static int endLineOf(Node node) {
        return node.getRange().map(range -> range.end.line).orElse(lineOf(node));
    }

    public static int countLines(String content) {
        if (content == null || content.isEmpty()) {
            return 0;
        }
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
        String withoutTrailing = normalized.endsWith("\n")
                ? normalized.substring(0, normalized.length() - 1) : normalized;
        return withoutTrailing.split("\n", -1).length;
    }
}
