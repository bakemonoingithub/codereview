package com.codereview.probe;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * JavaParser 技术实验探针（M0 任务 4）。
 * <p>
 * 目标：验证 JavaParser 能否解析目标项目的真实 Java 代码，并抽出以下三样结构信息——
 * <ol>
 *   <li>class / interface / enum / record 等类型及其名称、继承/实现关系、注解；</li>
 *   <li>import 依赖边（区分项目内 / JDK / 三方，并标记通配与静态导入）；</li>
 *   <li>字段与公共方法签名。</li>
 * </ol>
 * 输出对齐未来 M3 的 {@code STRUCT_SUMMARY} / {@code DEP_GRAPH} 共享物料。
 */
public final class JavaParserProbe {

    static {
        // 目标项目锁定 Java 17，显式设置语言级别以支持 record / sealed / switch 表达式 / text block
        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
    }

    // ------------------------------------------------------------------
    // 结果模型（record，直接 JSON 序列化）
    // ------------------------------------------------------------------

    public record ProbeResult(
            int totalFiles,
            int successFiles,
            int failedFiles,
            String projectRootPackage,
            List<FileSummary> files,
            List<Failure> failures) {
    }

    public record Failure(String relativePath, String error) {
    }

    public record FileSummary(
            String relativePath,
            String packageName,
            List<ImportSummary> imports,
            List<TypeSummary> types) {
    }

    public record ImportSummary(String name, boolean wildcard, boolean staticImport, String kind) {
    }

    public record TypeSummary(
            String kind,
            String name,
            List<String> typeParameters,
            List<String> annotations,
            List<String> extendedTypes,
            List<String> implementedTypes,
            List<FieldSummary> fields,
            List<String> methods) {
    }

    public record FieldSummary(String type, String name) {
    }

    // ------------------------------------------------------------------
    // 公共入口
    // ------------------------------------------------------------------

    /** 解析单段源码（供测试与最小样例使用）。 */
    public static CompilationUnit parse(String source) {
        return StaticJavaParser.parse(source);
    }

    /** 扫描目录下所有 .java 文件并解析。 */
    public ProbeResult probe(Path root) throws IOException {
        List<Path> javaFiles;
        try (Stream<Path> walk = Files.walk(root)) {
            javaFiles = walk
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }

        // 先算一遍所有文件 package 的最长公共前缀，作为"项目内"依赖的判定根
        String projectRoot = computeCommonPackageRoot(root, javaFiles);

        List<FileSummary> files = new ArrayList<>();
        List<Failure> failures = new ArrayList<>();
        int success = 0;

        for (Path file : javaFiles) {
            String rel = root.relativize(file).toString().replace('\\', '/');
            try {
                CompilationUnit cu = StaticJavaParser.parse(file);
                files.add(summarize(cu, rel, projectRoot));
                success++;
            } catch (Exception e) {
                failures.add(new Failure(rel, e.getClass().getSimpleName() + ": " + e.getMessage()));
            }
        }

        return new ProbeResult(javaFiles.size(), success, javaFiles.size() - success, projectRoot, files, failures);
    }

    /** 把单个 CompilationUnit 抽成结构摘要。 */
    public static FileSummary summarize(CompilationUnit cu, String relativePath, String projectRootPackage) {
        String packageName = cu.getPackageDeclaration()
                .map(pd -> pd.getNameAsString())
                .orElse("");

        List<ImportSummary> imports = new ArrayList<>();
        for (ImportDeclaration imp : cu.getImports()) {
            imports.add(new ImportSummary(
                    imp.getNameAsString(),
                    imp.isAsterisk(),
                    imp.isStatic(),
                    classifyImport(imp.getNameAsString(), projectRootPackage)));
        }

        List<TypeSummary> types = new ArrayList<>();
        for (TypeDeclaration<?> type : cu.getTypes()) {
            types.add(summarizeType(type));
        }

        return new FileSummary(relativePath, packageName, imports, types);
    }

    /** CLI：java -cp ... JavaParserProbe <rootDir> [outputJson] */
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("用法: JavaParserProbe <rootDir> [outputJson]");
            System.exit(2);
        }
        Path root = Path.of(args[0]);
        ProbeResult result = new JavaParserProbe().probe(root);

        // 控制台摘要
        System.out.printf("项目包根: %s%n", result.projectRootPackage());
        System.out.printf("文件总数: %d | 成功: %d | 失败: %d%n",
                result.totalFiles(), result.successFiles(), result.failedFiles());
        int typeCount = result.files().stream().mapToInt(f -> f.types().size()).sum();
        int methodCount = result.files().stream().flatMap(f -> f.types().stream())
                .mapToInt(t -> t.methods().size()).sum();
        int fieldCount = result.files().stream().flatMap(f -> f.types().stream())
                .mapToInt(t -> t.fields().size()).sum();
        System.out.printf("类型: %d | 方法: %d | 字段: %d%n", typeCount, methodCount, fieldCount);
        if (!result.failures().isEmpty()) {
            System.out.println("--- 失败文件 ---");
            result.failures().forEach(f -> System.out.println("  " + f.relativePath() + " -> " + f.error()));
        }

        // JSON 报告
        String out = args.length >= 2 ? args[1] : "java-parser-probe-report.json";
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of(out).toFile(), result);
        System.out.println("JSON 报告已写出: " + out);
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    private static TypeSummary summarizeType(TypeDeclaration<?> type) {
        String kind;
        List<String> typeParameters = new ArrayList<>();
        List<String> extended = new ArrayList<>();
        List<String> implemented = new ArrayList<>();
        List<FieldSummary> fields = new ArrayList<>();
        List<String> methods = new ArrayList<>();

        if (type instanceof ClassOrInterfaceDeclaration c) {
            kind = c.isInterface() ? "interface" : "class";
            c.getTypeParameters().forEach(tp -> typeParameters.add(tp.getNameAsString()));
            c.getExtendedTypes().forEach(t -> extended.add(t.asString()));
            c.getImplementedTypes().forEach(t -> implemented.add(t.asString()));
            for (FieldDeclaration fd : c.getFields()) {
                fd.getVariables().forEach(v -> fields.add(new FieldSummary(v.getType().asString(), v.getNameAsString())));
            }
            for (MethodDeclaration md : c.getMethods()) {
                methods.add(signature(md));
            }
        } else if (type instanceof EnumDeclaration e) {
            kind = "enum";
            e.getImplementedTypes().forEach(t -> implemented.add(t.asString()));
            for (FieldDeclaration fd : e.getFields()) {
                fd.getVariables().forEach(v -> fields.add(new FieldSummary(v.getType().asString(), v.getNameAsString())));
            }
            for (MethodDeclaration md : e.getMethods()) {
                methods.add(signature(md));
            }
        } else if (type instanceof RecordDeclaration r) {
            kind = "record";
            r.getTypeParameters().forEach(tp -> typeParameters.add(tp.getNameAsString()));
            r.getImplementedTypes().forEach(t -> implemented.add(t.asString()));
            // record 组件即字段
            for (Parameter p : r.getParameters()) {
                fields.add(new FieldSummary(p.getType().asString(), p.getNameAsString()));
            }
            for (MethodDeclaration md : r.getMethods()) {
                methods.add(signature(md));
            }
        } else if (type instanceof AnnotationDeclaration) {
            kind = "annotation";
        } else {
            kind = "type";
        }

        List<String> annotations = new ArrayList<>();
        type.getAnnotations().forEach(a -> annotations.add(a.getNameAsString()));

        return new TypeSummary(
                kind, type.getNameAsString(), typeParameters, annotations,
                extended, implemented, fields, methods);
    }

    private static String signature(MethodDeclaration md) {
        List<String> params = new ArrayList<>();
        for (Parameter p : md.getParameters()) {
            params.add(p.getType().asString());
        }
        String prefix = md.isStatic() ? "static " : "";
        return prefix + md.getType().asString() + " " + md.getNameAsString()
                + "(" + String.join(", ", params) + ")";
    }

    /** 依赖边分类：项目内 / JDK / 三方。 */
    static String classifyImport(String importName, String projectRootPackage) {
        if (projectRootPackage != null && !projectRootPackage.isBlank()
                && importName.startsWith(projectRootPackage)) {
            return "internal";
        }
        if (importName.startsWith("java.")) {
            return "jdk";
        }
        return "external";
    }

    private static String computeCommonPackageRoot(Path root, List<Path> javaFiles) {
        String common = null;
        for (Path f : javaFiles) {
            String pkg;
            try {
                CompilationUnit cu = StaticJavaParser.parse(f);
                pkg = cu.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse("");
            } catch (Exception e) {
                continue;
            }
            if (common == null) {
                common = pkg;
            } else {
                common = longestCommonPackagePrefix(common, pkg);
            }
            if (common.isEmpty()) {
                break;
            }
        }
        return common == null ? "" : common;
    }

    private static String longestCommonPackagePrefix(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        StringBuilder sb = new StringBuilder();
        int n = Math.min(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            if (pa[i].equals(pb[i])) {
                if (sb.length() > 0) {
                    sb.append('.');
                }
                sb.append(pa[i]);
            } else {
                break;
            }
        }
        return sb.toString();
    }
}
