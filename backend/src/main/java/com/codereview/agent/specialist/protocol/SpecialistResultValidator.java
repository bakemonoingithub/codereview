package com.codereview.agent.specialist.protocol;

import com.codereview.agent.api.ApiException;
import com.codereview.agent.api.ErrorCode;
import com.codereview.agent.specialist.material.SpecialistMaterials;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.codereview.agent.specialist.material.SpecialistMaterials.Material;
import static com.codereview.agent.specialist.material.SpecialistMaterials.Side;

/**
 * 三类专项协议的结构与位置校验。
 *
 * <p>只检查可观察事实：字段类型、枚举、必填、目标/辅助角色和原始行号范围。
 * 不做语义真伪、模式合理性或耦合解释质量判断；结构合规也不代表结论正确。
 *
 * <p>位置规则：
 * <ul>
 *   <li>问题 {@code locations} 必须是目标材料；辅助文件只能出现在 {@code evidence}；</li>
 *   <li>模式每个候选至少一处目标依据；</li>
 *   <li>差异新增文件不能用 BEFORE，删除文件不能用 AFTER；</li>
 *   <li>差异问题必须有 changeRelation，文件审查必须为 null。</li>
 * </ul>
 */
@Component
public class SpecialistResultValidator {

    private static final Set<String> CATEGORIES = Set.of("CORRECTNESS", "SECURITY", "RELIABILITY",
            "PERFORMANCE", "MAINTAINABILITY", "RULE");
    private static final Set<String> SEVERITIES = Set.of("HIGH", "MEDIUM", "LOW");
    private static final Set<String> FAMILIES = Set.of("CREATIONAL", "STRUCTURAL", "BEHAVIORAL",
            "OTHER");

    public ValidatedResult validate(SpecialistResults.Result result, ValidationContext context) {
        if (result == null) {
            throw invalid("EMPTY_RESULT", "缺少专项结果");
        }
        Map<String, Material> materials = materials(context);
        requireText(result.summary(), "summary");
        requireList(result.limitations(), "limitations");
        for (String limitation : result.limitations()) {
            requireText(limitation, "limitations[]");
        }
        if (result instanceof SpecialistResults.IssueResult issueResult) {
            validateIssues(issueResult, context, materials);
        } else if (result instanceof SpecialistResults.PatternResult patternResult) {
            validatePatterns(patternResult, context, materials);
        } else if (result instanceof SpecialistResults.CouplingResult couplingResult) {
            validateCoupling(couplingResult, context, materials);
        } else {
            throw invalid("UNKNOWN_RESULT", "未知专项结果类型");
        }
        return new ValidatedResult(result, Map.copyOf(materials), SpecialistProtocol.VERSION);
    }

    private void validateIssues(SpecialistResults.IssueResult result, ValidationContext context,
                                Map<String, Material> materials) {
        requireList(result.issues(), "issues");
        int index = 0;
        for (SpecialistResults.Issue issue : result.issues()) {
            String field = "issues[" + index + "]";
            if (issue == null) {
                throw invalid("NULL_ITEM", field + " 不能为 null");
            }
            requireEnum(issue.category(), CATEGORIES, field + ".category");
            requireEnum(issue.severity(), SEVERITIES, field + ".severity");
            requireText(issue.title(), field + ".title");
            requireText(issue.reason(), field + ".reason");
            requireText(issue.suggestion(), field + ".suggestion");
            requireList(issue.locations(), field + ".locations");
            if (issue.locations().isEmpty()) {
                throw invalid("MISSING_TARGET", field + " 至少需要一个目标位置");
            }
            for (int i = 0; i < issue.locations().size(); i++) {
                location(issue.locations().get(i), true, context, materials,
                        field + ".locations[" + i + "]");
            }
            List<SpecialistResults.Location> evidence = issue.evidence() == null
                    ? List.of() : issue.evidence();
            for (int i = 0; i < evidence.size(); i++) {
                location(evidence.get(i), false, context, materials,
                        field + ".evidence[" + i + "]");
            }
            validateChangeRelation(issue.changeRelation(), context, field + ".changeRelation");
            index++;
        }
    }

    private void validatePatterns(SpecialistResults.PatternResult result, ValidationContext context,
                                 Map<String, Material> materials) {
        requireList(result.patterns(), "patterns");
        int index = 0;
        for (SpecialistResults.PatternCandidate pattern : result.patterns()) {
            String field = "patterns[" + index + "]";
            if (pattern == null) {
                throw invalid("NULL_ITEM", field + " 不能为 null");
            }
            requireText(pattern.name(), field + ".name");
            requireEnum(pattern.family(), FAMILIES, field + ".family");
            requireText(pattern.role(), field + ".role");
            requireText(pattern.explanation(), field + ".explanation");
            requireList(pattern.evidence(), field + ".evidence");
            if (pattern.evidence().isEmpty()) {
                throw invalid("MISSING_TARGET", field + " 至少需要一处目标依据");
            }
            boolean targetEvidence = false;
            for (int i = 0; i < pattern.evidence().size(); i++) {
                Material material = location(pattern.evidence().get(i), false, context, materials,
                        field + ".evidence[" + i + "]");
                targetEvidence |= material.target();
            }
            if (!targetEvidence) {
                throw invalid("MISSING_TARGET", field + " 的依据必须包含目标材料，不能只有辅助材料");
            }
            index++;
        }
    }

    private void validateCoupling(SpecialistResults.CouplingResult result,
                                  ValidationContext context, Map<String, Material> materials) {
        requireList(result.observations(), "observations");
        Set<String> facts = context.dependencyIds() == null ? Set.of() : context.dependencyIds();
        int index = 0;
        for (SpecialistResults.CouplingObservation observation : result.observations()) {
            String field = "observations[" + index + "]";
            if (observation == null) {
                throw invalid("NULL_ITEM", field + " 不能为 null");
            }
            requireText(observation.title(), field + ".title");
            requireText(observation.explanation(), field + ".explanation");
            requireList(observation.dependencyIds(), field + ".dependencyIds");
            for (String dependencyId : observation.dependencyIds()) {
                if (!facts.contains(dependencyId)) {
                    throw invalid("UNKNOWN_DEPENDENCY",
                            field + " 引用了未计算出的 dependencyId：" + dependencyId);
                }
            }
            requireList(observation.evidence(), field + ".evidence");
            if (observation.evidence().isEmpty()) {
                throw invalid("MISSING_TARGET", field + " 至少需要一处代码依据");
            }
            for (int i = 0; i < observation.evidence().size(); i++) {
                location(observation.evidence().get(i), false, context, materials,
                        field + ".evidence[" + i + "]");
            }
            index++;
        }
    }

    private Material location(SpecialistResults.Location location, boolean targetOnly,
                              ValidationContext context, Map<String, Material> materials,
                              String field) {
        if (location == null) {
            throw invalid("NULL_LOCATION", field + " 不能为 null");
        }
        requireText(location.materialId(), field + ".materialId");
        Material material = materials.get(location.materialId());
        if (material == null) {
            throw invalid("UNKNOWN_MATERIAL",
                    field + " 引用了未发送的材料：" + location.materialId());
        }
        if (targetOnly && !material.target()) {
            throw invalid("AUXILIARY_AS_TARGET",
                    field + " 不能把辅助材料当作问题目标：" + location.materialId());
        }
        if (location.startLine() < 1 || location.endLine() < location.startLine()) {
            throw invalid("INVALID_RANGE", field + " 行范围非法："
                    + location.startLine() + "-" + location.endLine());
        }
        if (location.startLine() < material.startLine()
                || location.endLine() > material.endLine()) {
            throw invalid("OUT_OF_RANGE", field + " 超出材料 " + material.id() + " 的范围 "
                    + material.startLine() + "-" + material.endLine() + "："
                    + location.startLine() + "-" + location.endLine());
        }
        validateSide(material, context, field);
        return material;
    }

    private void validateSide(Material material, ValidationContext context, String field) {
        if ("FILES".equals(context.sourceKind())) {
            if (material.side() != Side.FILE) {
                throw invalid("SIDE_NOT_AVAILABLE",
                        field + " 文件审查只能引用 FILE 侧：" + material.side());
            }
            return;
        }
        if (context.added() && material.side() == Side.BEFORE) {
            throw invalid("SIDE_NOT_AVAILABLE", field + " 新增文件没有 BEFORE 侧");
        }
        if (context.removed() && material.side() == Side.AFTER) {
            throw invalid("SIDE_NOT_AVAILABLE", field + " 删除文件没有 AFTER 侧");
        }
    }

    private void validateChangeRelation(String changeRelation, ValidationContext context,
                                        String field) {
        if ("DIFF".equals(context.sourceKind())) {
            requireText(changeRelation, field);
        } else if (changeRelation != null && !changeRelation.isBlank()) {
            throw invalid("UNEXPECTED_CHANGE_RELATION",
                    field + " 在文件审查中必须为 null");
        }
    }

    private static Map<String, Material> materials(ValidationContext context) {
        Map<String, Material> map = new LinkedHashMap<>();
        for (Material material : context.materials()) {
            if (map.put(material.id(), material) != null) {
                throw invalid("DUPLICATE_MATERIAL", "材料 ID 重复：" + material.id());
            }
        }
        return map;
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw invalid("MISSING_FIELD", field + " 不能为空");
        }
    }

    private static void requireList(List<?> value, String field) {
        if (value == null) {
            throw invalid("MISSING_FIELD", field + " 必须是数组");
        }
    }

    private static void requireEnum(String value, Set<String> allowed, String field) {
        if (value == null || !allowed.contains(value)) {
            throw invalid("INVALID_ENUM", field + " 只能是 " + String.join("/", allowed)
                    + "，实际为 " + value);
        }
    }

    static ApiException invalid(String code, String detail) {
        return new ApiException(ErrorCode.MODEL_OUTPUT_INVALID,
                "模型输出不符合专项协议：" + detail,
                Map.of("protocolCode", code, "detail", detail == null ? "" : detail));
    }

    public record ValidationContext(String specialistType, String sourceKind, String headSha,
                                    String baseSha, String path, String oldPath, boolean added,
                                    boolean removed, List<Material> materials,
                                    Set<String> dependencyIds) {
        public ValidationContext {
            materials = materials == null ? List.of() : List.copyOf(materials);
            dependencyIds = dependencyIds == null ? Set.of() : Set.copyOf(dependencyIds);
        }
    }

    public record ResolvedLocation(String materialId, String path, String sha, String side,
                                   int startLine, int endLine) {
    }

    public record ValidatedResult(SpecialistResults.Result result, Map<String, Material> materials,
                                  String schemaVersion) {

        public List<ResolvedLocation> resolvedLocations() {
            List<ResolvedLocation> resolved = new ArrayList<>();
            if (result instanceof SpecialistResults.IssueResult issues) {
                for (SpecialistResults.Issue issue : issues.issues()) {
                    for (SpecialistResults.Location location : issue.locations()) {
                        resolved.add(resolve(location));
                    }
                    for (SpecialistResults.Location location : issue.evidence()) {
                        resolved.add(resolve(location));
                    }
                }
            } else if (result instanceof SpecialistResults.PatternResult patterns) {
                for (SpecialistResults.PatternCandidate pattern : patterns.patterns()) {
                    for (SpecialistResults.Location location : pattern.evidence()) {
                        resolved.add(resolve(location));
                    }
                }
            } else if (result instanceof SpecialistResults.CouplingResult coupling) {
                for (SpecialistResults.CouplingObservation observation : coupling.observations()) {
                    for (SpecialistResults.Location location : observation.evidence()) {
                        resolved.add(resolve(location));
                    }
                }
            }
            return resolved;
        }

        private ResolvedLocation resolve(SpecialistResults.Location location) {
            Material material = materials.get(location.materialId());
            return new ResolvedLocation(material.id(), material.path(), material.sha(),
                    material.side().name(), location.startLine(), location.endLine());
        }
    }
}
