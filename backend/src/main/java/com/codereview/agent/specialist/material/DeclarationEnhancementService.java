package com.codereview.agent.specialist.material;

import com.codereview.agent.repository.RepositoryReadService;
import com.codereview.agent.specialist.SpecialistProperties;
import com.codereview.agent.support.AgentIds;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.codereview.agent.specialist.material.SpecialistMaterials.Gap;
import static com.codereview.agent.specialist.material.SpecialistMaterials.Material;
import static com.codereview.agent.specialist.material.SpecialistMaterials.PreparedTarget;
import static com.codereview.agent.specialist.material.SpecialistMaterials.Role;
import static com.codereview.agent.specialist.material.SpecialistMaterials.Side;
import static com.codereview.agent.specialist.material.SpecialistMaterials.Status;

/**
 * 有限声明增强：目标 → 父类型/接口（广度优先，受深度、文件数、字节量限制）→
 * 字段/参数/返回类型的一层声明。
 *
 * <p>不递归调查完整业务链，不抓取第三方源码，不把“找不到”写成“不存在”。
 * 每个祖先只发送声明摘要（方法体用占位行省略），并保留原始行号；
 * 目标文件内已包含的类型不重复加入。
 */
@Service
@ConditionalOnProperty(prefix = "agent.datasource", name = "url")
public class DeclarationEnhancementService {

    private final MaterialStore store;
    private final RepositoryReadService repository;
    private final JavaStructureParser parser;
    private final DeclarationSummaryBuilder summaries;
    private final SpecialistProperties properties;

    public DeclarationEnhancementService(MaterialStore store, RepositoryReadService repository,
                                         JavaStructureParser parser,
                                         DeclarationSummaryBuilder summaries,
                                         SpecialistProperties properties) {
        this.store = store;
        this.repository = repository;
        this.parser = parser;
        this.summaries = summaries;
        this.properties = properties;
    }

    public PreparedTarget enhance(PreparedTarget target) {
        if (target.failed() || target.targets().isEmpty()) {
            return target;
        }
        Map<String, DeclarationResolver> resolvers = new LinkedHashMap<>();
        Set<String> targetPaths = new LinkedHashSet<>();
        for (Material material : target.targets()) {
            targetPaths.add(material.path());
        }

        List<Candidate> ancestors = new ArrayList<>();
        List<Candidate> direct = new ArrayList<>();
        List<Gap> gaps = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        int[] budget = {0};

        for (Material material : target.targets()) {
            if (material.structure() == null || material.sha() == null) {
                continue;
            }
            DeclarationResolver resolver = resolvers.computeIfAbsent(material.sha(),
                    sha -> new DeclarationResolver(target.projectId(), sha, repository, parser));
            collectAncestors(material, resolver, targetPaths, ancestors, gaps, seen, budget);
        }

        if (properties.isIncludeDirectDeclarations()) {
            for (Material material : target.targets()) {
                if (material.structure() == null || material.sha() == null) {
                    continue;
                }
                DeclarationResolver resolver = resolvers.computeIfAbsent(material.sha(),
                        sha -> new DeclarationResolver(target.projectId(), sha, repository, parser));
                collectDirect(material, resolver, targetPaths, direct, gaps, seen, budget);
            }
        }

        List<Material> auxiliary = new ArrayList<>();
        int files = 0;
        int bytes = 0;
        boolean limited = false;
        List<Candidate> ordered = new ArrayList<>(ancestors);
        ordered.addAll(direct);
        for (Candidate candidate : ordered) {
            if (files >= properties.getMaxAuxiliaryFiles()) {
                limited = true;
                break;
            }
            DeclarationSummaryBuilder.Summary summary;
            try {
                summary = summaries.build(candidate.resolved().content(), candidate.resolved().type());
            } catch (RuntimeException failure) {
                gaps.add(new Gap(Status.PARSE_FAILED.name(), candidate.resolved().displayName(),
                        "声明摘要生成失败：" + failure.getMessage()));
                continue;
            }
            int size = summary.content().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (bytes + size > properties.getMaxAuxiliaryBytes()) {
                limited = true;
                break;
            }
            Material material = new Material(AgentIds.next(), target.projectId(),
                    candidate.resolved().sha(), candidate.resolved().path(), candidate.side(),
                    summary.startLine(), summary.endLine(), summary.content(),
                    ContentHash.sha256(summary.content()), "DECLARATION_SUMMARY",
                    "仅类型声明与方法签名；方法体以占位行省略", Role.AUXILIARY, Status.RESOLVED,
                    candidate.resolved().qualifiedName(), candidate.resolved().structure());
            auxiliary.add(store.save(material));
            files++;
            bytes += size;
        }
        if (limited) {
            gaps.add(new Gap(Status.LIMIT_REACHED.name(), target.path(),
                    "辅助材料达到上限：文件 " + properties.getMaxAuxiliaryFiles()
                            + " 个或 " + properties.getMaxAuxiliaryBytes() + " 字节；后续分支停止"));
        }
        return target.withAuxiliary(auxiliary, gaps);
    }

    private void collectAncestors(Material targetMaterial, DeclarationResolver resolver,
                                  Set<String> targetPaths, List<Candidate> out, List<Gap> gaps,
                                  Set<String> seen, int[] budget) {
        Deque<QueueItem> queue = new ArrayDeque<>();
        for (JavaSourceStructure.TypeDecl type : targetMaterial.structure().types()) {
            for (JavaSourceStructure.TypeRef ref : concat(type.extendedTypes(), type.implementedTypes())) {
                queue.add(new QueueItem(ref, targetMaterial.structure(), 1, targetMaterial.side()));
            }
        }
        while (!queue.isEmpty()) {
            QueueItem item = queue.removeFirst();
            if (item.depth() > properties.getMaxInheritanceDepth()) {
                gaps.add(new Gap(Status.LIMIT_REACHED.name(), item.ref().raw(),
                        "超过继承深度 " + properties.getMaxInheritanceDepth() + "，停止该分支"));
                continue;
            }
            String base = TypeNames.erasure(item.ref().raw());
            if (base.isBlank() || TypeNames.isJdkBasic(base)) {
                continue;
            }
            DeclarationResolver.Resolved resolved = resolver.resolve(base, item.source());
            if (resolved.status() == Status.RESOLVED) {
                if (targetPaths.contains(resolved.path())) {
                    continue;
                }
                String key = resolved.sha() + "|" + resolved.path() + "|" + resolved.qualifiedName();
                if (!seen.add(key)) {
                    continue;
                }
                if (budget[0] >= properties.getMaxAuxiliaryFiles()) {
                    gaps.add(new Gap(Status.LIMIT_REACHED.name(), resolved.displayName(),
                            "辅助文件达到 " + properties.getMaxAuxiliaryFiles() + " 个，停止扩展"));
                    continue;
                }
                budget[0]++;
                out.add(new Candidate(resolved, item.side()));
                for (JavaSourceStructure.TypeDecl type : resolved.structure().types()) {
                    if (!type.qualifiedName().equals(resolved.qualifiedName())) {
                        continue;
                    }
                    for (JavaSourceStructure.TypeRef ref : concat(type.extendedTypes(),
                            type.implementedTypes())) {
                        queue.add(new QueueItem(ref, resolved.structure(), item.depth() + 1,
                                item.side()));
                    }
                }
            } else if (resolved.actionableGap()) {
                gaps.add(new Gap(resolved.status().name(), resolved.displayName(),
                        resolved.diagnostic()));
            }
        }
    }

    private void collectDirect(Material targetMaterial, DeclarationResolver resolver,
                               Set<String> targetPaths, List<Candidate> out, List<Gap> gaps,
                               Set<String> seen, int[] budget) {
        Set<String> missing = new LinkedHashSet<>();
        for (JavaSourceStructure.TypeDecl type : targetMaterial.structure().types()) {
            List<JavaSourceStructure.TypeRef> refs = new ArrayList<>();
            for (JavaSourceStructure.FieldDecl field : type.fields()) {
                refs.add(new JavaSourceStructure.TypeRef(field.type(), field.line()));
            }
            for (JavaSourceStructure.MethodDecl method : type.methods()) {
                refs.add(new JavaSourceStructure.TypeRef(method.returnType(), method.startLine()));
                for (String parameter : method.parameterTypes()) {
                    refs.add(new JavaSourceStructure.TypeRef(parameter, method.startLine()));
                }
            }
            for (JavaSourceStructure.TypeRef ref : refs) {
                for (String name : TypeNames.allNames(ref.raw())) {
                    if (name.isBlank() || TypeNames.isJdkBasic(name)) {
                        continue;
                    }
                    DeclarationResolver.Resolved resolved = resolver.resolve(name,
                            targetMaterial.structure());
                    if (resolved.status() == Status.RESOLVED) {
                        if (targetPaths.contains(resolved.path())) {
                            continue;
                        }
                        String key = resolved.sha() + "|" + resolved.path() + "|"
                                + resolved.qualifiedName();
                        if (!seen.add(key)) {
                            continue;
                        }
                        if (budget[0] >= properties.getMaxAuxiliaryFiles()) {
                            gaps.add(new Gap(Status.LIMIT_REACHED.name(), resolved.displayName(),
                                    "辅助文件达到 " + properties.getMaxAuxiliaryFiles()
                                            + " 个，停止直接声明补充"));
                            return;
                        }
                        budget[0]++;
                        out.add(new Candidate(resolved, targetMaterial.side()));
                    } else if (resolved.status() == Status.NOT_AVAILABLE) {
                        missing.add(TypeNames.erasure(name));
                    } else if (resolved.actionableGap()) {
                        gaps.add(new Gap(resolved.status().name(), resolved.displayName(),
                                resolved.diagnostic()));
                    }
                }
            }
        }
        if (!missing.isEmpty()) {
            List<String> names = missing.stream().limit(10).toList();
            gaps.add(new Gap(Status.NOT_AVAILABLE.name(), targetMaterial.path(),
                    "直接引用类型未在仓库版本中找到（可能为第三方或未提交源码）："
                            + String.join(", ", names)
                            + (missing.size() > names.size() ? " 等 " + missing.size() + " 个" : "")));
        }
    }

    @SafeVarargs
    private static <T> List<T> concat(List<T>... lists) {
        List<T> result = new ArrayList<>();
        for (List<T> list : lists) {
            result.addAll(list);
        }
        return result;
    }

    private record QueueItem(JavaSourceStructure.TypeRef ref, JavaSourceStructure source,
                             int depth, Side side) {
    }

    private record Candidate(DeclarationResolver.Resolved resolved, Side side) {
    }
}
