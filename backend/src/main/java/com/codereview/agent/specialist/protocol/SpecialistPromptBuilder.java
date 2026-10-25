package com.codereview.agent.specialist.protocol;

import com.codereview.agent.prompt.PromptFact;
import com.codereview.agent.prompt.PromptInput;
import com.codereview.agent.prompt.PromptMaterial;
import com.codereview.agent.prompt.PromptPurpose;
import com.codereview.agent.specialist.coupling.CouplingFacts;
import com.codereview.agent.specialist.material.SpecialistMaterials;
import com.codereview.agent.support.SpecialistTypes;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;

import static com.codereview.agent.specialist.material.SpecialistMaterials.PreparedTarget;

/**
 * 把准备好的材料、通用规则与依赖事实转成统一组装输入。
 *
 * <p>用途由专项类型和来源决定（问题审查分文件/差异），协议版本固定；
 * 目标与辅助材料不混列，缺失说明只来自实际准备结果。
 */
@Component
public class SpecialistPromptBuilder {

    public PromptInput build(PreparedTarget target, CouplingFacts facts,
                             String generalRuleName, String generalRuleContent) {
        return build(target, facts, generalRuleName, generalRuleContent, null);
    }

    /** 格式重试时把上一次实际错误和返回片段带回组装，仍走同一入口。 */
    public PromptInput build(PreparedTarget target, CouplingFacts facts,
                             String generalRuleName, String generalRuleContent,
                             PromptInput.FormatRetry formatRetry) {
        List<PromptMaterial> targets = target.targets().stream()
                .map(SpecialistMaterials.Material::toPromptMaterial).toList();
        List<PromptMaterial> auxiliary = target.auxiliary().stream()
                .map(SpecialistMaterials.Material::toPromptMaterial).toList();
        List<PromptFact> promptFacts = facts == null ? List.of() : facts.promptFacts();
        Set<String> missingNotes = new LinkedHashSet<>(target.gapNotes());
        if (facts != null) {
            missingNotes.addAll(facts.limitations());
        }
        return new PromptInput(purpose(target), SpecialistProtocol.VERSION, generalRuleName,
                generalRuleContent, target.note(), target.sourceKind(), target.branch(),
                target.headSha(), target.baseSha(), targets, auxiliary, List.copyOf(missingNotes),
                promptFacts, formatRetry);
    }

    public SpecialistResultValidator.ValidationContext context(PreparedTarget target,
                                                               CouplingFacts facts) {
        Set<String> dependencyIds = facts == null ? Set.of() : facts.dependencyIds();
        return new SpecialistResultValidator.ValidationContext(target.specialistType(),
                target.sourceKind(), target.headSha(), target.baseSha(), target.path(),
                target.oldPath(), target.added(), target.removed(), target.allMaterials(),
                dependencyIds);
    }

    static PromptPurpose purpose(PreparedTarget target) {
        return switch (target.specialistType()) {
            case SpecialistTypes.ISSUE -> "DIFF".equals(target.sourceKind())
                    ? PromptPurpose.ISSUE_DIFF : PromptPurpose.ISSUE_FILE;
            case SpecialistTypes.PATTERN -> PromptPurpose.PATTERN;
            case SpecialistTypes.COUPLING -> PromptPurpose.COUPLING;
            default -> throw new IllegalArgumentException("未知专项类型：" + target.specialistType());
        };
    }
}
