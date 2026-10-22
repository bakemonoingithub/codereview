package com.codereview.agent.specialist.material;

import com.codereview.agent.repository.RepositoryReadService;
import com.codereview.agent.repository.RepositorySnapshots;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DeclarationResolverTest {

    private static final String PROJECT = "01J9Z6Q3W4X5Y6Z7A8B9C0D1E2";
    private static final String SHA = "b7a312e0c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6";

    private RepositoryReadService repository;
    private JavaStructureParser parser;
    private DeclarationResolver resolver;

    @BeforeEach
    void setUp() {
        repository = mock(RepositoryReadService.class);
        parser = new JavaStructureParser();
        resolver = new DeclarationResolver(PROJECT, SHA, repository, parser);
        when(repository.listPaths(PROJECT, SHA)).thenReturn(paths(
                "app/Child.java", "app/Parent.java", "app/Api.java", "app/Outer.java",
                "p1/Dup.java", "p2/Dup.java", "broken/Broken.java"));
        read("app/Parent.java", """
                package app;
                public class Parent {
                }
                """);
        read("app/Api.java", """
                package app;
                public interface Api {
                }
                """);
        read("app/Outer.java", """
                package app;
                public class Outer {
                    public static class Inner {
                    }
                }
                """);
        read("p1/Dup.java", """
                package p1;
                public class Dup {
                }
                """);
        read("p2/Dup.java", """
                package p2;
                public class Dup {
                }
                """);
        read("broken/Broken.java", "package broken;\nclass Broken { void x( }\n");
    }

    @Test
    void explicitImportWinsOverWildcard() {
        JavaSourceStructure source = parse("app/Child.java", """
                package app;
                import app.Parent;
                import p1.*;
                import p2.*;
                public class Child extends Parent {
                }
                """);

        DeclarationResolver.Resolved resolved = resolver.resolve("Parent", source);

        assertEquals(SpecialistMaterials.Status.RESOLVED, resolved.status());
        assertEquals("app.Parent", resolved.qualifiedName());
        assertEquals("app/Parent.java", resolved.path());
    }

    @Test
    void samePackageIsResolvedWithoutImport() {
        JavaSourceStructure source = parse("app/Child.java", """
                package app;
                public class Child extends Api {
                }
                """);

        assertEquals("app.Api", resolver.resolve("Api", source).qualifiedName());
    }

    @Test
    void wildcardDuplicateIsAmbiguousAndFirstIsNotChosen() {
        JavaSourceStructure source = parse("app/Child.java", """
                package app;
                import p1.*;
                import p2.*;
                public class Child {
                    Dup dup;
                }
                """);

        DeclarationResolver.Resolved resolved = resolver.resolve("Dup", source);

        assertEquals(SpecialistMaterials.Status.AMBIGUOUS, resolved.status());
        assertTrue(resolved.diagnostic().contains("歧义") || resolved.diagnostic().contains("多个"));
    }

    @Test
    void nestedImportVerifiesTheDeclaredNestedType() {
        JavaSourceStructure source = parse("app/Child.java", """
                package app;
                import app.Outer.Inner;
                public class Child {
                    Inner inner;
                }
                """);

        DeclarationResolver.Resolved resolved = resolver.resolve("Inner", source);

        assertEquals(SpecialistMaterials.Status.RESOLVED, resolved.status());
        assertEquals("app.Outer.Inner", resolved.qualifiedName());
        assertEquals("app/Outer.java", resolved.path());
    }

    @Test
    void sameFileTypeIsInternalAndNotAuxiliary() {
        JavaSourceStructure source = parse("app/Child.java", """
                package app;
                public class Child {
                    Inner inner;
                    static class Inner {
                    }
                }
                """);

        assertEquals(SpecialistMaterials.Status.INTERNAL, resolver.resolve("Inner", source).status());
    }

    @Test
    void missingThirdPartyIsNotAvailableRatherThanNonexistent() {
        JavaSourceStructure source = parse("app/Child.java", """
                package app;
                public class Child {
                    com.vendor.Missing missing;
                }
                """);

        DeclarationResolver.Resolved resolved = resolver.resolve("com.vendor.Missing", source);

        assertEquals(SpecialistMaterials.Status.NOT_AVAILABLE, resolved.status());
        assertTrue(resolved.diagnostic().contains("未在版本") || resolved.diagnostic().contains("第三方"));
    }

    @Test
    void candidateParseFailureIsReportedAsParseFailed() {
        JavaSourceStructure source = parse("broken/Child.java", """
                package broken;
                public class Child extends Broken {
                }
                """);

        DeclarationResolver.Resolved resolved = resolver.resolve("Broken", source);

        assertEquals(SpecialistMaterials.Status.PARSE_FAILED, resolved.status());
    }

    @Test
    void packageMismatchIsNotAcceptedByFileNameAlone() {
        read("app/Parent.java", """
                package app;
                public class Parent {
                }
                """);
        JavaSourceStructure source = parse("other/Child.java", """
                package other;
                import app.Parent;
                public class Child extends Parent {
                }
                """);

        assertEquals("app.Parent", resolver.resolve("Parent", source).qualifiedName());
    }

    private JavaSourceStructure parse(String path, String content) {
        return parser.parse(path, content);
    }

    private void read(String path, String content) {
        when(repository.readFile(PROJECT, SHA, path))
                .thenReturn(new RepositorySnapshots.FileSnapshot(SHA, path, content,
                        ContentHash.sha256(content), content.length(), 1));
    }

    private static RepositorySnapshots.PathList paths(String... paths) {
        return new RepositorySnapshots.PathList(java.util.Arrays.stream(paths)
                .map(path -> new RepositorySnapshots.PathEntry(path, "blob", true))
                .toList(), SHA, false);
    }
}
