package com.codereview.agent.specialist.coupling;

import com.codereview.agent.api.ApiException;
import com.codereview.agent.api.ErrorCode;
import com.codereview.agent.specialist.material.DeclarationResolver;
import com.codereview.agent.specialist.material.JavaSourceStructure;
import com.codereview.agent.specialist.material.SpecialistMaterials;
import com.codereview.agent.specialist.material.TypeNames;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.codereview.agent.specialist.material.SpecialistMaterials.Material;
import static com.codereview.agent.specialist.material.SpecialistMaterials.Side;

/**
 * 由目标文件 AST 计算可核对的文件级依赖事实。
 *
 * <p>边只来自目标类型声明可见的结构：父类型、字段、方法参数／返回、显式泛型参数与
 * {@code new} 类型。导入语句本身不算使用，注释、字符串、局部隐式类型和反射不算已解析边。
 * 去掉目标文件内部类型与 Java 基础类型；泛型擦除后按规范化类型名去重。
 *
 * <p>定位失败的类型保留为 unresolved，不把同名类型合并成确定身份；AST 无法生成时
 * 直接失败，不能返回零依赖。
 */
@Component
public class CouplingFactsBuilder {

    public CouplingFacts build(SpecialistMaterials.PreparedTarget target,
                               DeclarationResolver resolver) {
        Material material = targetMaterial(target);
        if (material == null || material.structure() == null) {
            throw new ApiException(ErrorCode.UNSUPPORTED_LANGUAGE,
                    "耦合度分析需要目标 Java AST：" + target.path(),
                    Map.of("path", target.path()));
        }
        JavaSourceStructure structure = material.structure();
        Set<String> targetTypes = new LinkedHashSet<>();
        for (JavaSourceStructure.TypeDecl type : structure.types()) {
            targetTypes.add(type.qualifiedName());
        }

        List<Ref> refs = collect(structure);
        Set<String> resolvedTypes = new LinkedHashSet<>();
        Set<String> unresolvedTypes = new LinkedHashSet<>();
        Map<String, Set<String>> relationTypes = new LinkedHashMap<>();
        Map<String, CouplingFacts.Edge> edges = new LinkedHashMap<>();
        Map<String, String> unresolvedDetails = new LinkedHashMap<>();

        for (Ref ref : refs) {
            List<String> names = TypeNames.allNames(ref.raw());
            for (String name : names) {
                if (name.isBlank() || TypeNames.isJdkBasic(name)) {
                    continue;
                }
                if (structure.declares(simpleName(name))) {
                    continue;
                }
                String relation = name.equals(ref.raw()) ? ref.relation() : "GENERIC";
                DeclarationResolver.Resolved resolved = resolver.resolve(name, structure);
                if (resolved.status() == SpecialistMaterials.Status.RESOLVED) {
                    if (resolved.path().equals(material.path())) {
                        continue;
                    }
                    resolvedTypes.add(resolved.qualifiedName());
                    relationTypes.computeIfAbsent(relation, key -> new LinkedHashSet<>())
                            .add(resolved.qualifiedName());
                    String key = ref.sourceType() + "|" + resolved.qualifiedName() + "|" + relation;
                    edges.putIfAbsent(key, new CouplingFacts.Edge(null, ref.sourceType(),
                            resolved.qualifiedName(), resolved.path(), relation, ref.line()));
                } else if (resolved.actionableGap()) {
                    String normalized = TypeNames.erasure(name);
                    if (normalized.isBlank()) {
                        continue;
                    }
                    unresolvedTypes.add(normalized);
                    unresolvedDetails.putIfAbsent(normalized,
                            resolved.status().name() + "：" + resolved.diagnostic());
                }
            }
        }

        List<CouplingFacts.Edge> ordered = new ArrayList<>(edges.values());
        ordered.sort(Comparator.comparing(CouplingFacts.Edge::relation)
                .thenComparing(CouplingFacts.Edge::targetType)
                .thenComparingInt(CouplingFacts.Edge::line));
        List<CouplingFacts.Edge> numbered = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) {
            CouplingFacts.Edge edge = ordered.get(i);
            numbered.add(new CouplingFacts.Edge("dep-" + (i + 1), edge.sourceType(),
                    edge.targetType(), edge.targetPath(), edge.relation(), edge.line()));
        }

        Map<String, Integer> relationCounts = new LinkedHashMap<>();
        relationTypes.forEach((relation, types) -> relationCounts.put(relation, types.size()));
        int denominator = resolvedTypes.size() + unresolvedTypes.size();
        double coverage = denominator == 0 ? 1.0 : (double) resolvedTypes.size() / denominator;

        List<String> limitations = new ArrayList<>();
        if (!unresolvedTypes.isEmpty()) {
            limitations.add(unresolvedTypes.size() + " 个类型未能可靠定位（"
                    + String.join("；", unresolvedDetails.entrySet().stream().limit(5)
                    .map(entry -> entry.getKey() + "=" + entry.getValue()).toList())
                    + "），指标未覆盖全部出向依赖");
        }
        if (numbered.isEmpty() && !unresolvedTypes.isEmpty()) {
            limitations.add("没有生成仓库内已解析依赖边，不能把本结果解释为零依赖");
        }
        return new CouplingFacts(numbered, resolvedTypes.size(), unresolvedTypes.size(),
                relationCounts, coverage, material.sha(), material.side().name(), limitations);
    }

    private static Material targetMaterial(SpecialistMaterials.PreparedTarget target) {
        for (Material material : target.targets()) {
            if (material.side() == Side.AFTER) {
                return material;
            }
        }
        for (Material material : target.targets()) {
            if (material.side() == Side.FILE) {
                return material;
            }
        }
        return target.targets().stream().filter(material -> material.side() == Side.BEFORE)
                .findFirst().orElse(null);
    }

    private static List<Ref> collect(JavaSourceStructure structure) {
        List<Ref> refs = new ArrayList<>();
        for (JavaSourceStructure.TypeDecl type : structure.types()) {
            for (JavaSourceStructure.TypeRef parent : type.extendedTypes()) {
                refs.add(new Ref(parent.raw(), "INHERITANCE", parent.line(), type.qualifiedName()));
            }
            for (JavaSourceStructure.TypeRef parent : type.implementedTypes()) {
                refs.add(new Ref(parent.raw(), "INHERITANCE", parent.line(), type.qualifiedName()));
            }
            for (JavaSourceStructure.FieldDecl field : type.fields()) {
                refs.add(new Ref(field.type(), "FIELD", field.line(), type.qualifiedName()));
            }
            for (JavaSourceStructure.MethodDecl method : type.methods()) {
                refs.add(new Ref(method.returnType(), "SIGNATURE", method.startLine(),
                        type.qualifiedName()));
                for (String parameter : method.parameterTypes()) {
                    refs.add(new Ref(parameter, "SIGNATURE", method.startLine(),
                            type.qualifiedName()));
                }
            }
            for (JavaSourceStructure.TypeRef creation : type.objectCreations()) {
                refs.add(new Ref(creation.raw(), "NEW", creation.line(), type.qualifiedName()));
            }
        }
        return refs;
    }

    private static String simpleName(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(dot + 1);
    }

    private record Ref(String raw, String relation, int line, String sourceType) {
    }
}
