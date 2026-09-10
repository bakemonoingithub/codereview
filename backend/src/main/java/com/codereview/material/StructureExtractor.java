package com.codereview.material;

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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * JavaParser 结构抽取（由 M0 探针产品化）：解析源码 → 类/接口/枚举/record + 内部依赖边 + 紧凑结构摘要。
 */
public final class StructureExtractor {

    static {
        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
    }

    public Material extract(List<SourceFile> files) {
        List<ParsedFile> parsed = new ArrayList<>();
        for (SourceFile f : files) {
            try {
                parsed.add(parse(f));
            } catch (Exception ignored) {
                // 解析失败跳过（M0 探针已验证真实项目可解析）
            }
        }
        String rootPackage = commonPackageRoot(parsed);
        Set<String> internalFqcns = new HashSet<>();
        for (ParsedFile pf : parsed) {
            for (ClassInfo c : pf.classes()) {
                internalFqcns.add(c.fqcn());
            }
        }
        List<ClassInfo> classes = new ArrayList<>();
        List<DepEdge> edges = new ArrayList<>();
        for (ParsedFile pf : parsed) {
            for (ClassInfo c : pf.classes()) {
                classes.add(c);
                for (String imp : pf.imports()) {
                    if (internalFqcns.contains(imp) && !imp.equals(c.fqcn())) {
                        edges.add(new DepEdge(c.fqcn(), imp));
                    }
                }
            }
        }
        return new Material(rootPackage, classes, edges, buildSummary(classes, rootPackage));
    }

    private ParsedFile parse(SourceFile f) {
        CompilationUnit cu = StaticJavaParser.parse(f.content());
        String packageName = cu.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse("");
        List<String> imports = new ArrayList<>();
        for (ImportDeclaration imp : cu.getImports()) {
            if (!imp.isAsterisk() && !imp.isStatic()) {
                imports.add(imp.getNameAsString());
            }
        }
        List<ClassInfo> classes = new ArrayList<>();
        for (TypeDeclaration<?> type : cu.getTypes()) {
            classes.add(toClassInfo(f.path(), packageName, type));
        }
        return new ParsedFile(f.path(), packageName, imports, classes);
    }

    private ClassInfo toClassInfo(String path, String packageName, TypeDeclaration<?> type) {
        String kind;
        List<String> annotations = new ArrayList<>();
        List<String> extended = new ArrayList<>();
        List<String> implemented = new ArrayList<>();
        List<ClassInfo.Field> fields = new ArrayList<>();
        List<String> methods = new ArrayList<>();

        if (type instanceof ClassOrInterfaceDeclaration c) {
            kind = c.isInterface() ? "interface" : "class";
            c.getExtendedTypes().forEach(t -> extended.add(t.asString()));
            c.getImplementedTypes().forEach(t -> implemented.add(t.asString()));
            for (FieldDeclaration fd : c.getFields()) {
                fd.getVariables().forEach(v -> fields.add(new ClassInfo.Field(v.getType().asString(), v.getNameAsString())));
            }
            for (MethodDeclaration md : c.getMethods()) {
                methods.add(signature(md));
            }
        } else if (type instanceof EnumDeclaration e) {
            kind = "enum";
            e.getImplementedTypes().forEach(t -> implemented.add(t.asString()));
            for (FieldDeclaration fd : e.getFields()) {
                fd.getVariables().forEach(v -> fields.add(new ClassInfo.Field(v.getType().asString(), v.getNameAsString())));
            }
            for (MethodDeclaration md : e.getMethods()) {
                methods.add(signature(md));
            }
        } else if (type instanceof RecordDeclaration r) {
            kind = "record";
            r.getImplementedTypes().forEach(t -> implemented.add(t.asString()));
            for (Parameter p : r.getParameters()) {
                fields.add(new ClassInfo.Field(p.getType().asString(), p.getNameAsString()));
            }
            for (MethodDeclaration md : r.getMethods()) {
                methods.add(signature(md));
            }
        } else if (type instanceof AnnotationDeclaration) {
            kind = "annotation";
        } else {
            kind = "type";
        }
        type.getAnnotations().forEach(a -> annotations.add(a.getNameAsString()));
        return new ClassInfo(path, packageName, type.getNameAsString(), kind, annotations, extended, implemented, fields, methods);
    }

    private static String signature(MethodDeclaration md) {
        List<String> params = new ArrayList<>();
        for (Parameter p : md.getParameters()) {
            params.add(p.getType().asString());
        }
        String prefix = md.isStatic() ? "static " : "";
        return prefix + md.getType().asString() + " " + md.getNameAsString() + "(" + String.join(", ", params) + ")";
    }

    private String commonPackageRoot(List<ParsedFile> parsed) {
        String common = null;
        for (ParsedFile pf : parsed) {
            if (common == null) {
                common = pf.packageName();
            } else {
                common = longestCommonPrefix(common, pf.packageName());
            }
            if (common.isEmpty()) {
                break;
            }
        }
        return common == null ? "" : common;
    }

    private static String longestCommonPrefix(String a, String b) {
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

    private String buildSummary(List<ClassInfo> classes, String rootPackage) {
        StringBuilder sb = new StringBuilder();
        sb.append("项目包根: ").append(rootPackage).append("\n\n");
        for (ClassInfo c : classes) {
            sb.append('[').append(c.kind()).append("] ").append(c.fqcn()).append('\n');
            if (!c.annotations().isEmpty()) {
                sb.append("  @").append(String.join(" @", c.annotations())).append('\n');
            }
            if (!c.extendedTypes().isEmpty()) {
                sb.append("  extends ").append(String.join(", ", c.extendedTypes())).append('\n');
            }
            if (!c.implementedTypes().isEmpty()) {
                sb.append("  implements ").append(String.join(", ", c.implementedTypes())).append('\n');
            }
            for (ClassInfo.Field f : c.fields()) {
                sb.append("  field ").append(f.type()).append(' ').append(f.name()).append('\n');
            }
            for (String m : c.methods()) {
                sb.append("  method ").append(m).append('\n');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private record ParsedFile(String path, String packageName, List<String> imports, List<ClassInfo> classes) {
    }
}
