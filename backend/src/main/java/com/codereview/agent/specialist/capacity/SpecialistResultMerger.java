package com.codereview.agent.specialist.capacity;

import com.codereview.agent.specialist.material.SpecialistMaterials;
import com.codereview.agent.specialist.protocol.SpecialistResultValidator;
import com.codereview.agent.specialist.protocol.SpecialistResults;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 片段结果归并。
 *
 * <p>只合并能确定的重复项：问题按“位置 + 类别 + 规范化标题”，模式按“名称 + 角色 + 依据位置”，
 * 耦合解释按“标题 + 解释 + dependencyIds”。同位置不同原因不强行合并；不调用 LLM 综合复查，
 * 也不把片段结果伪装成整文件结论。
 */
@Component
public class SpecialistResultMerger {

    public SpecialistResults.Result merge(String specialistType,
                                          List<SpecialistResultValidator.ValidatedResult> parts) {
        if (parts == null || parts.isEmpty()) {
            throw new IllegalArgumentException("没有可归并的片段结果");
        }
        String summary = parts.stream().map(part -> part.result().summary())
                .filter(text -> text != null && !text.isBlank()).distinct()
                .collect(java.util.stream.Collectors.joining("\n\n"));
        List<String> limitations = new ArrayList<>();
        Set<String> seenLimitations = new LinkedHashSet<>();
        for (SpecialistResultValidator.ValidatedResult part : parts) {
            for (String limitation : part.result().limitations()) {
                if (seenLimitations.add(limitation)) {
                    limitations.add(limitation);
                }
            }
        }
        return switch (specialistType) {
            case "ISSUE" -> mergeIssues(summary, parts, limitations);
            case "PATTERN" -> mergePatterns(summary, parts, limitations);
            case "COUPLING" -> mergeCoupling(summary, parts, limitations);
            default -> throw new IllegalArgumentException("未知专项类型：" + specialistType);
        };
    }

    private SpecialistResults.IssueResult mergeIssues(
            String summary, List<SpecialistResultValidator.ValidatedResult> parts,
            List<String> limitations) {
        List<SpecialistResults.Issue> issues = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (SpecialistResultValidator.ValidatedResult part : parts) {
            if (!(part.result() instanceof SpecialistResults.IssueResult result)) {
                continue;
            }
            for (SpecialistResults.Issue issue : result.issues()) {
                String key = issueKey(issue, part.materials());
                if (seen.add(key)) {
                    issues.add(issue);
                }
            }
        }
        return new SpecialistResults.IssueResult(summary, issues, limitations);
    }

    private SpecialistResults.PatternResult mergePatterns(
            String summary, List<SpecialistResultValidator.ValidatedResult> parts,
            List<String> limitations) {
        List<SpecialistResults.PatternCandidate> patterns = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (SpecialistResultValidator.ValidatedResult part : parts) {
            if (!(part.result() instanceof SpecialistResults.PatternResult result)) {
                continue;
            }
            for (SpecialistResults.PatternCandidate pattern : result.patterns()) {
                String key = normalized(pattern.name()) + "|" + normalized(pattern.role()) + "|"
                        + locationKey(pattern.evidence(), part.materials());
                if (seen.add(key)) {
                    patterns.add(pattern);
                }
            }
        }
        return new SpecialistResults.PatternResult(summary, patterns, limitations);
    }

    private SpecialistResults.CouplingResult mergeCoupling(
            String summary, List<SpecialistResultValidator.ValidatedResult> parts,
            List<String> limitations) {
        List<SpecialistResults.CouplingObservation> observations = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (SpecialistResultValidator.ValidatedResult part : parts) {
            if (!(part.result() instanceof SpecialistResults.CouplingResult result)) {
                continue;
            }
            for (SpecialistResults.CouplingObservation observation : result.observations()) {
                String key = normalized(observation.title()) + "|"
                        + normalized(observation.explanation()) + "|"
                        + String.join(",", observation.dependencyIds());
                if (seen.add(key)) {
                    observations.add(observation);
                }
            }
        }
        return new SpecialistResults.CouplingResult(summary, observations, limitations);
    }

    private static String locationKey(List<SpecialistResults.Location> locations,
                                      Map<String, SpecialistMaterials.Material> materials) {
        return locations.stream()
                .map(location -> {
                    SpecialistMaterials.Material material = materials.get(location.materialId());
                    String path = material == null ? location.materialId() : material.path();
                    String side = material == null ? "?" : material.side().name();
                    return path + ":" + (material == null ? "?" : material.sha()) + ":"
                            + side + ":" + location.startLine() + "-" + location.endLine();
                })
                .sorted()
                .reduce((left, right) -> left + "," + right).orElse("");
    }

    private static String normalized(String text) {
        return text == null ? "" : text.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    public static String issueKey(SpecialistResults.Issue issue,
                                   Map<String, SpecialistMaterials.Material> materials) {
        return List.of(issue.category(), issue.severity(), normalized(issue.title()),
                issue.reason().strip(), issue.suggestion() == null ? "" : issue.suggestion().strip(),
                issue.changeRelation() == null ? "" : issue.changeRelation().strip(), locationKey(issue.locations(), materials),
                locationKey(issue.evidence(), materials)).toString();
    }
}
