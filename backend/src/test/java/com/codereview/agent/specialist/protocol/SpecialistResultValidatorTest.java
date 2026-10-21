package com.codereview.agent.specialist.protocol;

import com.codereview.agent.api.ApiException;
import com.codereview.agent.specialist.material.SpecialistMaterials;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpecialistResultValidatorTest {

    private static final String SHA = "b7a312e0c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6";
    private static final String BASE = "12ac80f0a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6";

    private final SpecialistResultParser parser = new SpecialistResultParser();
    private final SpecialistResultValidator validator = new SpecialistResultValidator();

    private final SpecialistMaterials.Material target = material("t1", "src/Order.java",
            SpecialistMaterials.Side.FILE, SpecialistMaterials.Role.TARGET, 1, 20);
    private final SpecialistMaterials.Material auxiliary = material("a1", "src/Base.java",
            SpecialistMaterials.Side.FILE, SpecialistMaterials.Role.AUXILIARY, 1, 10);

    @Test
    void acceptsIssuesWithTargetLocationsAndAuxiliaryEvidence() {
        SpecialistResults.Result result = parser.parse("""
                {"summary":"x","issues":[{"category":"RULE","severity":"MEDIUM","title":"t",
                "locations":[{"materialId":"t1","startLine":2,"endLine":3}],
                "reason":"r","suggestion":"s",
                "evidence":[{"materialId":"a1","startLine":1,"endLine":2}],
                "changeRelation":null}],"limitations":[]}
                """, "ISSUE");

        SpecialistResultValidator.ValidatedResult validated = validator.validate(result,
                filesContext(List.of(target, auxiliary), false, false));

        assertEquals("1", validated.schemaVersion());
        assertEquals(2, validated.resolvedLocations().size());
        assertEquals("src/Order.java", validated.resolvedLocations().get(0).path());
        assertEquals("FILE", validated.resolvedLocations().get(0).side());
    }

    @Test
    void auxiliaryCannotBeTheProblemTarget() {
        SpecialistResults.Result result = parser.parse("""
                {"summary":"x","issues":[{"category":"RULE","severity":"MEDIUM","title":"t",
                "locations":[{"materialId":"a1","startLine":1,"endLine":2}],
                "reason":"r","suggestion":"s","evidence":[],"changeRelation":null}],
                "limitations":[]}
                """, "ISSUE");

        ApiException failure = assertThrows(ApiException.class,
                () -> validator.validate(result, filesContext(List.of(target, auxiliary), false, false)));
        assertEquals("AUXILIARY_AS_TARGET", code(failure));
    }

    @Test
    void locationOutsideTheSentMaterialIsRejected() {
        SpecialistResults.Result result = parser.parse("""
                {"summary":"x","issues":[{"category":"RULE","severity":"MEDIUM","title":"t",
                "locations":[{"materialId":"t1","startLine":19,"endLine":21}],
                "reason":"r","suggestion":"s","evidence":[],"changeRelation":null}],
                "limitations":[]}
                """, "ISSUE");

        assertEquals("OUT_OF_RANGE", code(assertThrows(ApiException.class,
                () -> validator.validate(result, filesContext(List.of(target), false, false)))));
    }

    @Test
    void unknownMaterialIsRejected() {
        SpecialistResults.Result result = parser.parse("""
                {"summary":"x","issues":[{"category":"RULE","severity":"MEDIUM","title":"t",
                "locations":[{"materialId":"missing","startLine":1,"endLine":1}],
                "reason":"r","suggestion":"s","evidence":[],"changeRelation":null}],
                "limitations":[]}
                """, "ISSUE");

        assertEquals("UNKNOWN_MATERIAL", code(assertThrows(ApiException.class,
                () -> validator.validate(result, filesContext(List.of(target), false, false)))));
    }

    @Test
    void diffIssueRequiresChangeRelation() {
        SpecialistMaterials.Material after = material("after", "src/Order.java",
                SpecialistMaterials.Side.AFTER, SpecialistMaterials.Role.TARGET, 1, 20);
        SpecialistResults.Result result = parser.parse("""
                {"summary":"x","issues":[{"category":"RULE","severity":"MEDIUM","title":"t",
                "locations":[{"materialId":"after","startLine":2,"endLine":3}],
                "reason":"r","suggestion":"s","evidence":[],"changeRelation":null}],
                "limitations":[]}
                """, "ISSUE");

        assertEquals("MISSING_FIELD", code(assertThrows(ApiException.class,
                () -> validator.validate(result, diffContext(List.of(after), false, false)))));
    }

    @Test
    void fileIssueMustNotCarryChangeRelation() {
        SpecialistResults.Result result = parser.parse("""
                {"summary":"x","issues":[{"category":"RULE","severity":"MEDIUM","title":"t",
                "locations":[{"materialId":"t1","startLine":2,"endLine":3}],
                "reason":"r","suggestion":"s","evidence":[],"changeRelation":"与本次变更有关"}],
                "limitations":[]}
                """, "ISSUE");

        assertEquals("UNEXPECTED_CHANGE_RELATION", code(assertThrows(ApiException.class,
                () -> validator.validate(result, filesContext(List.of(target), false, false)))));
    }

    @Test
    void addedFileCannotUseBeforeSide() {
        SpecialistMaterials.Material before = material("before", "src/Old.java",
                SpecialistMaterials.Side.BEFORE, SpecialistMaterials.Role.TARGET, 1, 20);
        SpecialistResults.Result result = diffIssue("before");

        assertEquals("SIDE_NOT_AVAILABLE", code(assertThrows(ApiException.class,
                () -> validator.validate(result, diffContext(List.of(before), true, false)))));
    }

    @Test
    void removedFileCannotUseAfterSide() {
        SpecialistMaterials.Material after = material("after", "src/Old.java",
                SpecialistMaterials.Side.AFTER, SpecialistMaterials.Role.TARGET, 1, 20);
        SpecialistResults.Result result = diffIssue("after");

        assertEquals("SIDE_NOT_AVAILABLE", code(assertThrows(ApiException.class,
                () -> validator.validate(result, diffContext(List.of(after), false, true)))));
    }

    @Test
    void patternRequiresTargetEvidenceNotOnlyAuxiliary() {
        SpecialistResults.Result result = parser.parse("""
                {"summary":"x","patterns":[{"name":"Strategy","family":"BEHAVIORAL",
                "role":"r","explanation":"e",
                "evidence":[{"materialId":"a1","startLine":1,"endLine":2}]}],"limitations":[]}
                """, "PATTERN");

        assertEquals("MISSING_TARGET", code(assertThrows(ApiException.class,
                () -> validator.validate(result, filesContext(List.of(target, auxiliary), false, false)))));
    }

    @Test
    void couplingCanOnlyReferenceComputedDependencyIds() {
        SpecialistResults.Result result = parser.parse("""
                {"summary":"x","observations":[{"title":"t","explanation":"e",
                "dependencyIds":["dep-9"],
                "evidence":[{"materialId":"t1","startLine":1,"endLine":2}],"suggestion":null}],
                "limitations":[]}
                """, "COUPLING");

        assertEquals("UNKNOWN_DEPENDENCY", code(assertThrows(ApiException.class,
                () -> validator.validate(result, couplingContext(Set.of("dep-1"))))));
    }

    @Test
    void emptyResultArraysAreAllowed() {
        SpecialistResults.Result result = parser.parse("""
                {"summary":"未发现问题","issues":[],"limitations":[]}
                """, "ISSUE");

        SpecialistResultValidator.ValidatedResult validated = validator.validate(result,
                filesContext(List.of(target), false, false));

        assertTrue(validated.resolvedLocations().isEmpty());
    }

    private SpecialistResults.Result diffIssue(String materialId) {
        return parser.parse("""
                {"summary":"x","issues":[{"category":"RULE","severity":"MEDIUM","title":"t",
                "locations":[{"materialId":"%s","startLine":2,"endLine":3}],
                "reason":"r","suggestion":"s","evidence":[],"changeRelation":"删除了校验"}],
                "limitations":[]}
                """.formatted(materialId), "ISSUE");
    }

    private SpecialistResultValidator.ValidationContext filesContext(
            List<SpecialistMaterials.Material> materials, boolean added, boolean removed) {
        return new SpecialistResultValidator.ValidationContext("ISSUE", "FILES", SHA, null,
                "src/Order.java", null, added, removed, materials, Set.of());
    }

    private SpecialistResultValidator.ValidationContext diffContext(
            List<SpecialistMaterials.Material> materials, boolean added, boolean removed) {
        return new SpecialistResultValidator.ValidationContext("ISSUE", "DIFF", SHA, BASE,
                "src/Order.java", null, added, removed, materials, Set.of());
    }

    private SpecialistResultValidator.ValidationContext couplingContext(Set<String> dependencyIds) {
        return new SpecialistResultValidator.ValidationContext("COUPLING", "FILES", SHA, null,
                "src/Order.java", null, false, false, List.of(target), dependencyIds);
    }

    private static SpecialistMaterials.Material material(String id, String path,
                                                         SpecialistMaterials.Side side,
                                                         SpecialistMaterials.Role role,
                                                         int start, int end) {
        return new SpecialistMaterials.Material(id, "01J9Z6Q3W4X5Y6Z7A8B9C0D1E2", SHA, path,
                side, start, end, "code\n", "hash-" + id, "COMPLETE", null, role,
                SpecialistMaterials.Status.RESOLVED, null, null);
    }

    private static String code(ApiException failure) {
        Map<?, ?> details = (Map<?, ?>) failure.details();
        return String.valueOf(details.get("protocolCode"));
    }
}
