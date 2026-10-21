package com.codereview.agent.specialist.material;

import com.codereview.agent.repository.RepositoryReadService;
import com.codereview.agent.repository.RepositorySnapshots;
import com.codereview.agent.specialist.SpecialistProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DeclarationEnhancementServiceTest {

    private static final String PROJECT = "01J9Z6Q3W4X5Y6Z7A8B9C0D1E2";
    private static final String SHA = "b7a312e0c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6";

    private RepositoryReadService repository;
    private MaterialStore store;
    private JavaStructureParser parser;
    private SpecialistProperties properties;
    private DeclarationEnhancementService service;

    @BeforeEach
    void setUp() {
        repository = mock(RepositoryReadService.class);
        store = mock(MaterialStore.class);
        when(store.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        parser = new JavaStructureParser();
        properties = new SpecialistProperties();
        service = new DeclarationEnhancementService(store, repository, parser,
                new DeclarationSummaryBuilder(), properties);
        when(repository.listPaths(PROJECT, SHA)).thenReturn(paths("app/Child.java",
                "app/Parent.java", "app/Api.java", "app/Grand.java", "app/Root.java", "app/Order.java"));
        read("app/Parent.java", """
                package app;
                public class Parent extends Grand {
                    private String value;
                    public void run() {
                        System.out.println("parent-body");
                    }
                }
                """);
        read("app/Api.java", """
                package app;
                public interface Api extends Root {
                    void call();
                }
                """);
        read("app/Grand.java", """
                package app;
                public class Grand {
                    protected int count;
                }
                """);
        read("app/Root.java", """
                package app;
                public interface Root {
                }
                """);
        read("app/Order.java", """
                package app;
                public class Order {
                    private Long id;
                }
                """);
    }

    @Test
    void breadthFirstAncestorsAreSummarizedInOrder() {
        SpecialistMaterials.PreparedTarget target = target("""
                package app;
                public class Child extends Parent implements Api {
                    private Order order;
                }
                """);

        SpecialistMaterials.PreparedTarget enhanced = service.enhance(target);

        assertFalse(enhanced.failed());
        List<String> names = enhanced.auxiliary().stream()
                .map(SpecialistMaterials.Material::declaredType).toList();
        assertEquals(List.of("app.Parent", "app.Api", "app.Grand", "app.Root", "app.Order"), names);
        SpecialistMaterials.Material parent = enhanced.auxiliary().get(0);
        assertTrue(parent.content().contains("void run()"));
        assertTrue(parent.content().contains("// [实现省略]"));
        assertFalse(parent.content().contains("parent-body"));
        assertEquals("DECLARATION_SUMMARY", parent.completeness());
        assertEquals(2, parent.startLine());
        assertEquals(SpecialistMaterials.Side.FILE, parent.side());
    }

    @Test
    void depthLimitStopsDeepBranches() {
        properties.setMaxInheritanceDepth(1);

        SpecialistMaterials.PreparedTarget enhanced = service.enhance(target("""
                package app;
                public class Child extends Parent {
                }
                """));

        assertEquals(List.of("app.Parent"), enhanced.auxiliary().stream()
                .map(SpecialistMaterials.Material::declaredType).toList());
        assertTrue(enhanced.gaps().stream().anyMatch(gap ->
                gap.code().equals("LIMIT_REACHED") && gap.detail().contains("继承深度")));
    }

    @Test
    void fileBudgetStopsAfterTheConfiguredNumberOfAuxiliaryFiles() {
        properties.setMaxAuxiliaryFiles(1);

        SpecialistMaterials.PreparedTarget enhanced = service.enhance(target("""
                package app;
                public class Child extends Parent implements Api {
                }
                """));

        assertEquals(1, enhanced.auxiliary().size());
        assertTrue(enhanced.gaps().stream().anyMatch(gap -> gap.code().equals("LIMIT_REACHED")));
    }

    @Test
    void cycleAndDiamondAreVisitedOnce() {
        read("app/Parent.java", """
                package app;
                public class Parent implements Api {
                }
                """);
        read("app/Api.java", """
                package app;
                public interface Api extends Parent {
                }
                """);

        SpecialistMaterials.PreparedTarget enhanced = service.enhance(target("""
                package app;
                public class Child extends Parent implements Api {
                }
                """));

        assertEquals(2, enhanced.auxiliary().size());
    }

    @Test
    void missingParentIsReportedButDoesNotFailTheTarget() {
        read("app/Parent.java", """
                package app;
                public class Parent extends com.vendor.Missing {
                }
                """);

        SpecialistMaterials.PreparedTarget enhanced = service.enhance(target("""
                package app;
                public class Child extends Parent {
                }
                """));

        assertFalse(enhanced.failed());
        assertTrue(enhanced.auxiliary().stream()
                .anyMatch(material -> material.declaredType().equals("app.Parent")));
        assertTrue(enhanced.gaps().stream().anyMatch(gap ->
                gap.code().equals("NOT_AVAILABLE") && gap.subject().contains("Missing")));
    }

    @Test
    void targetFileIsNotAddedAgainAsAuxiliary() {
        SpecialistMaterials.PreparedTarget enhanced = service.enhance(target("""
                package app;
                public class Child extends Child {
                }
                """));

        assertTrue(enhanced.auxiliary().isEmpty());
    }

    private SpecialistMaterials.PreparedTarget target(String childContent) {
        JavaSourceStructure structure = parser.parse("app/Child.java", childContent);
        SpecialistMaterials.Material material = new SpecialistMaterials.Material(
                "01J9Z6Q3W4X5Y6Z7A8B9C0D1E3", PROJECT, SHA, "app/Child.java",
                SpecialistMaterials.Side.FILE, 1, JavaStructureParser.countLines(childContent),
                childContent, ContentHash.sha256(childContent), "COMPLETE", null,
                SpecialistMaterials.Role.TARGET, SpecialistMaterials.Status.RESOLVED, null, structure);
        return new SpecialistMaterials.PreparedTarget(PROJECT, "ISSUE", "FILES", "main",
                "app/Child.java", null, SHA, null, null, false, false,
                List.of(material), List.of(), List.of(), null, null);
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
