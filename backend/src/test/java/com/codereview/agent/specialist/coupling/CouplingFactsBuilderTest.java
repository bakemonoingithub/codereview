package com.codereview.agent.specialist.coupling;

import com.codereview.agent.api.ApiException;
import com.codereview.agent.api.ErrorCode;
import com.codereview.agent.repository.RepositoryReadService;
import com.codereview.agent.repository.RepositorySnapshots;
import com.codereview.agent.specialist.material.ContentHash;
import com.codereview.agent.specialist.material.DeclarationResolver;
import com.codereview.agent.specialist.material.JavaSourceStructure;
import com.codereview.agent.specialist.material.JavaStructureParser;
import com.codereview.agent.specialist.material.SpecialistMaterials;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CouplingFactsBuilderTest {

    private static final String PROJECT = "01J9Z6Q3W4X5Y6Z7A8B9C0D1E2";
    private static final String SHA = "b7a312e0c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6";
    private static final String CHILD = """
            package app;
            import java.util.List;

            public class Child extends Parent {
                private Order order;
                private List<Order> list;

                public Payment pay(Order input) {
                    return new Repo().payment(input);
                }
            }
            """;

    private final CouplingFactsBuilder builder = new CouplingFactsBuilder();
    private final JavaStructureParser parser = new JavaStructureParser();
    private RepositoryReadService repository;

    @BeforeEach
    void setUp() {
        repository = mock(RepositoryReadService.class);
        when(repository.listPaths(PROJECT, SHA)).thenReturn(paths("app/Child.java",
                "app/Parent.java", "app/Order.java", "app/Payment.java", "app/Repo.java",
                "app/OnlyMissing.java"));
        read("app/Parent.java", "package app;\npublic class Parent {\n}\n");
        read("app/Order.java", "package app;\npublic class Order {\n}\n");
        read("app/Payment.java", "package app;\npublic class Payment {\n}\n");
        read("app/Repo.java", "package app;\npublic class Repo {\n    Payment payment(Order order) {\n        return null;\n    }\n}\n");
        read("app/OnlyMissing.java", "package app;\npublic class OnlyMissing {\n    private com.vendor.Missing missing;\n}\n");
    }

    @Test
    void computesInheritanceFieldSignatureGenericAndNewEdges() {
        CouplingFacts facts = builder.build(target("app/Child.java", CHILD),
                new DeclarationResolver(PROJECT, SHA, repository, parser));

        assertEquals(4, facts.resolvedOutgoingCount());
        assertEquals(0, facts.unresolvedTypeCount());
        assertEquals(6, facts.edges().size());
        assertEquals("dep-1", facts.edges().get(0).id());
        assertEquals(1, facts.declarationRelationCounts().get("INHERITANCE"));
        assertEquals(1, facts.declarationRelationCounts().get("FIELD"));
        assertEquals(1, facts.declarationRelationCounts().get("GENERIC"));
        assertEquals(2, facts.declarationRelationCounts().get("SIGNATURE"));
        assertEquals(1, facts.declarationRelationCounts().get("NEW"));
        assertEquals(1.0, facts.coverage());
        assertTrue(facts.limitations().isEmpty());
        assertEquals(6, facts.promptFacts().size());
    }

    @Test
    void unresolvedTypesStayVisibleAndAreNotZeroDependencies() {
        CouplingFacts facts = builder.build(target("app/OnlyMissing.java",
                        "package app;\npublic class OnlyMissing {\n    private com.vendor.Missing missing;\n}\n"),
                new DeclarationResolver(PROJECT, SHA, repository, parser));

        assertEquals(0, facts.resolvedOutgoingCount());
        assertEquals(1, facts.unresolvedTypeCount());
        assertTrue(facts.edges().isEmpty());
        assertTrue(facts.limitations().stream().anyMatch(text -> text.contains("零依赖")));
        assertEquals(0.0, facts.coverage());
    }

    @Test
    void missingAstFailsInsteadOfReturningEmptyMetrics() {
        SpecialistMaterials.PreparedTarget target = target("app/Child.java", CHILD);
        SpecialistMaterials.Material material = target.targets().get(0);
        SpecialistMaterials.Material withoutStructure = new SpecialistMaterials.Material(
                material.id(), material.projectId(), material.sha(), material.path(),
                material.side(), material.startLine(), material.endLine(), material.content(),
                material.contentHash(), material.completeness(), material.diagnostic(),
                material.role(), material.status(), material.declaredType(), null);
        SpecialistMaterials.PreparedTarget broken = new SpecialistMaterials.PreparedTarget(
                target.projectId(), "COUPLING", "FILES", "main", target.path(), null, SHA, null,
                null, false, false, List.of(withoutStructure), List.of(), List.of(), null, null);

        ApiException failure = assertThrows(ApiException.class, () -> builder.build(broken,
                new DeclarationResolver(PROJECT, SHA, repository, parser)));
        assertEquals(ErrorCode.UNSUPPORTED_LANGUAGE, failure.errorCode());
    }

    private SpecialistMaterials.PreparedTarget target(String path, String content) {
        JavaSourceStructure structure = parser.parse(path, content);
        SpecialistMaterials.Material material = new SpecialistMaterials.Material(
                "01J9Z6Q3W4X5Y6Z7A8B9C0D1E3", PROJECT, SHA, path,
                SpecialistMaterials.Side.FILE, 1, JavaStructureParser.countLines(content),
                content, ContentHash.sha256(content), "COMPLETE", null,
                SpecialistMaterials.Role.TARGET, SpecialistMaterials.Status.RESOLVED, null, structure);
        return new SpecialistMaterials.PreparedTarget(PROJECT, "COUPLING", "FILES", "main",
                path, null, SHA, null, null, false, false, List.of(material), List.of(),
                List.of(), null, null);
    }

    private void read(String path, String content) {
        when(repository.readFile(PROJECT, SHA, path))
                .thenReturn(new RepositorySnapshots.FileSnapshot(SHA, path, content,
                        ContentHash.sha256(content), content.length(),
                        JavaStructureParser.countLines(content)));
    }

    private static RepositorySnapshots.PathList paths(String... paths) {
        return new RepositorySnapshots.PathList(java.util.Arrays.stream(paths)
                .map(path -> new RepositorySnapshots.PathEntry(path, "blob", true))
                .toList(), SHA, false);
    }
}
