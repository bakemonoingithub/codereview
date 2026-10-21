package com.codereview.agent.specialist.capacity;

import com.codereview.agent.specialist.material.SpecialistMaterials;
import com.codereview.agent.specialist.protocol.SpecialistResultValidator;
import com.codereview.agent.specialist.protocol.SpecialistResults;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class SpecialistResultMergerTest {

    private final SpecialistResultMerger merger = new SpecialistResultMerger();

    @Test
    void mergesOnlyCertainIssueDuplicatesAndKeepsDifferentReasons() {
        SpecialistResults.Issue first = issue("重复扣款", 10, 12, "同一位置同一原因");
        SpecialistResults.Issue duplicate = issue("重复扣款", 10, 12, "同一位置同一原因");
        SpecialistResults.Issue different = issue("重复扣款", 10, 12, "同一位置不同原因");
        SpecialistResults.IssueResult part = new SpecialistResults.IssueResult("结论",
                List.of(first, duplicate, different), List.of("第三方不可取得"));
        var validated = validated(part, "t1");

        SpecialistResults.IssueResult merged = assertInstanceOf(SpecialistResults.IssueResult.class,
                merger.merge("ISSUE", List.of(validated)));

        assertEquals(2, merged.issues().size());
        assertEquals(1, merged.limitations().size());
    }

    @Test
    void mergesSamePatternByNormalizedNameRoleAndEvidence() {
        SpecialistResults.PatternCandidate first = new SpecialistResults.PatternCandidate(
                "Strategy", "BEHAVIORAL", "策略接口", "解释",
                List.of(new SpecialistResults.Location("t1", 2, 4)));
        SpecialistResults.PatternCandidate second = new SpecialistResults.PatternCandidate(
                "strategy", "BEHAVIORAL", "策略接口", "另一个解释",
                List.of(new SpecialistResults.Location("t1", 2, 4)));
        SpecialistResults.PatternResult part = new SpecialistResults.PatternResult("结论",
                List.of(first, second), List.of());

        SpecialistResults.PatternResult merged = assertInstanceOf(SpecialistResults.PatternResult.class,
                merger.merge("PATTERN", List.of(validated(part, "t1"))));

        assertEquals(1, merged.patterns().size());
    }

    @Test
    void mergesCouplingObservationsWithSameDependencies() {
        SpecialistResults.CouplingObservation first = new SpecialistResults.CouplingObservation(
                "支付契约", "解释", List.of("dep-1"),
                List.of(new SpecialistResults.Location("t1", 1, 2)), null);
        SpecialistResults.CouplingObservation second = new SpecialistResults.CouplingObservation(
                "支付契约", "解释", List.of("dep-1"),
                List.of(new SpecialistResults.Location("t1", 1, 2)), null);
        SpecialistResults.CouplingResult part = new SpecialistResults.CouplingResult("结论",
                List.of(first, second), List.of());

        SpecialistResults.CouplingResult merged = assertInstanceOf(SpecialistResults.CouplingResult.class,
                merger.merge("COUPLING", List.of(validated(part, "t1"))));

        assertEquals(1, merged.observations().size());
    }

    private static SpecialistResults.Issue issue(String title, int start, int end, String reason) {
        return new SpecialistResults.Issue("CORRECTNESS", "HIGH", title,
                List.of(new SpecialistResults.Location("t1", start, end)), reason, "建议",
                List.of(), null);
    }

    private static SpecialistResultValidator.ValidatedResult validated(
            SpecialistResults.Result result, String materialId) {
        SpecialistMaterials.Material material = new SpecialistMaterials.Material(
                materialId, "01J9Z6Q3W4X5Y6Z7A8B9C0D1E2",
                "b7a312e0c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6", "src/A.java",
                SpecialistMaterials.Side.FILE, 1, 100, "code", "hash",
                "COMPLETE", null, SpecialistMaterials.Role.TARGET,
                SpecialistMaterials.Status.RESOLVED, null, null);
        return new SpecialistResultValidator.ValidatedResult(result,
                Map.of(materialId, material), "1");
    }
}
