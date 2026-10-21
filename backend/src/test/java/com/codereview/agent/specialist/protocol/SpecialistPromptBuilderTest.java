package com.codereview.agent.specialist.protocol;

import com.codereview.agent.prompt.AssembledPrompt;
import com.codereview.agent.prompt.PromptAssemblyService;
import com.codereview.agent.prompt.PromptInput;
import com.codereview.agent.prompt.PromptPurpose;
import com.codereview.agent.specialist.coupling.CouplingFacts;
import com.codereview.agent.specialist.material.SpecialistMaterials;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpecialistPromptBuilderTest {

    private static final String SHA = "b7a312e0c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6";
    private static final String BASE = "12ac80f0a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6";

    private final SpecialistPromptBuilder builder = new SpecialistPromptBuilder();

    @Test
    void diffIssueUsesDiffPurposeAndSeparatesFactsFromCode() {
        SpecialistMaterials.PreparedTarget target = new SpecialistMaterials.PreparedTarget(
                "01J9Z6Q3W4X5Y6Z7A8B9C0D1E2", "ISSUE", "DIFF", "main", "src/Order.java",
                "src/OldOrder.java", SHA, BASE, "重点看上锁", false, false,
                List.of(material("before", SpecialistMaterials.Side.BEFORE, "src/OldOrder.java"),
                        material("after", SpecialistMaterials.Side.AFTER, "src/Order.java")),
                List.of(), List.of(new SpecialistMaterials.Gap("PARSE_FAILED", "src/Base.java",
                        "结构解析失败")), null, null);
        CouplingFacts facts = new CouplingFacts(List.of(
                new CouplingFacts.Edge("dep-1", "app.Order", "app.Repo", "src/Repo.java",
                        "FIELD", 12)), 1, 0, Map.of("FIELD", 1), 1.0, SHA, "AFTER", List.of());

        PromptInput input = builder.build(target, facts, "策略", "规则");
        AssembledPrompt prompt = new PromptAssemblyService().assemble(input);

        assertEquals(PromptPurpose.ISSUE_DIFF, input.purpose());
        assertEquals(2, input.targets().size());
        assertEquals(1, input.facts().size());
        assertTrue(prompt.userPrompt().contains("## 程序计算的依赖事实"));
        assertTrue(prompt.userPrompt().contains("dep-1"));
        assertTrue(prompt.userPrompt().contains("## 缺失与限制"));
        assertTrue(prompt.userPrompt().contains("PARSE_FAILED"));

        SpecialistResultValidator.ValidationContext context = builder.context(target, facts);
        assertEquals(Set.of("dep-1"), context.dependencyIds());
        assertEquals("DIFF", context.sourceKind());
        assertEquals(2, context.materials().size());
    }

    @Test
    void patternAndCouplingKeepTheirOwnPurposes() {
        assertEquals(PromptPurpose.PATTERN, SpecialistPromptBuilder.purpose(target("PATTERN")));
        assertEquals(PromptPurpose.COUPLING, SpecialistPromptBuilder.purpose(target("COUPLING")));
    }

    private static SpecialistMaterials.PreparedTarget target(String type) {
        return new SpecialistMaterials.PreparedTarget("01J9Z6Q3W4X5Y6Z7A8B9C0D1E2", type,
                "FILES", "main", "src/A.java", null, SHA, null, null, false, false,
                List.of(material("t1", SpecialistMaterials.Side.FILE, "src/A.java")), List.of(),
                List.of(), null, null);
    }

    private static SpecialistMaterials.Material material(String id, SpecialistMaterials.Side side,
                                                         String path) {
        return new SpecialistMaterials.Material(id, "01J9Z6Q3W4X5Y6Z7A8B9C0D1E2", SHA, path,
                side, 1, 3, "code\n", "hash-" + id, "COMPLETE", null,
                SpecialistMaterials.Role.TARGET, SpecialistMaterials.Status.RESOLVED, null, null);
    }
}
