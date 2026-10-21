package com.codereview.agent.specialist;

import com.codereview.agent.api.ApiException;
import com.codereview.agent.api.ErrorCode;
import com.codereview.agent.repository.RepositoryReadService;
import com.codereview.agent.repository.RepositorySnapshots;
import com.codereview.agent.specialist.material.ContentHash;
import com.codereview.agent.specialist.material.DeclarationEnhancementService;
import com.codereview.agent.specialist.material.DeclarationResolver;
import com.codereview.agent.specialist.material.DeclarationSummaryBuilder;
import com.codereview.agent.specialist.material.JavaStructureParser;
import com.codereview.agent.specialist.material.MaterialPreparationService;
import com.codereview.agent.specialist.material.MaterialStore;
import com.codereview.agent.specialist.coupling.CouplingFacts;
import com.codereview.agent.specialist.coupling.CouplingFactsBuilder;
import com.codereview.agent.specialist.protocol.SpecialistPromptBuilder;
import com.codereview.agent.specialist.protocol.SpecialistResultParser;
import com.codereview.agent.specialist.protocol.SpecialistResultValidator;
import com.codereview.agent.prompt.PromptAssemblyService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static com.codereview.agent.specialist.material.SpecialistMaterials.Material;
import static com.codereview.agent.specialist.material.SpecialistMaterials.PrepareCommand;
import static com.codereview.agent.specialist.material.SpecialistMaterials.PreparedTarget;
import static com.codereview.agent.specialist.material.SpecialistMaterials.Role;
import static com.codereview.agent.specialist.material.SpecialistMaterials.Side;
import static com.codereview.agent.specialist.material.SpecialistMaterials.Status;

class SpecialistBoundaryRegressionTest {
    private static final String P = "project";
    private static final String SHA = "b7a312e0c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6";
    private static final String BASE = "12ac80f0a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6";
    private final JavaStructureParser parser = new JavaStructureParser();
    private final RepositoryReadService repo = mock(RepositoryReadService.class);
    private final MaterialStore store = material -> material;

    private void files(Map<String, String> sources) {
        when(repo.listPaths(P, SHA)).thenReturn(new RepositorySnapshots.PathList(sources.keySet().stream()
                .map(path -> new RepositorySnapshots.PathEntry(path, "blob", true)).toList(), SHA, false));
        sources.forEach((path, code) -> when(repo.readFile(P, SHA, path)).thenReturn(
                new RepositorySnapshots.FileSnapshot(SHA, path, code, ContentHash.sha256(code),
                        code.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                        JavaStructureParser.countLines(code))));
    }
    private PreparedTarget target(String code) {
        Material material = new Material("target",P,SHA,"app/Child.java",Side.FILE,1,
            Math.max(1,JavaStructureParser.countLines(code)),code,ContentHash.sha256(code),"COMPLETE",null,
            Role.TARGET,Status.RESOLVED,null,parser.parse("app/Child.java",code));
        return new PreparedTarget(P,"COUPLING","FILES","main",material.path(),null,SHA,null,null,
            false,false,List.of(material),List.of(),List.of(),null,null);
    }
    private CouplingFacts facts(String code) {
        return new CouplingFactsBuilder().build(target(code),new DeclarationResolver(P,SHA,repo,parser));
    }

    @Test
    void findsDeclarationsUnderMavenSourceRoot() {
        files(Map.of("src/main/java/app/Parent.java","package app; public class Parent {}"));
        var source = parser.parse("src/main/java/app/Child.java","package app; class Child extends Parent {}");
        assertEquals(Status.RESOLVED,new DeclarationResolver(P,SHA,repo,parser).resolve("Parent",source).status());
    }
    @Test
    void duplicateSourceRootsAreAmbiguous() {
        files(Map.of("one/src/main/java/app/Parent.java","package app; public class Parent {}",
            "two/src/main/java/app/Parent.java","package app; public class Parent {}"));
        var source = parser.parse("app/Child.java","package app; class Child extends Parent {}");
        assertEquals(Status.AMBIGUOUS,new DeclarationResolver(P,SHA,repo,parser).resolve("Parent",source).status());
    }
    @Test
    void qualifiedExternalTypeIsNotCollapsedIntoLocalSimpleName() {
        files(Map.of("vendor/Port.java","package vendor; public interface Port {}"));
        var f = facts("package app; class Child { vendor.Port port; static class Port {} }");
        assertEquals(1,f.resolvedOutgoingCount());
        assertEquals("vendor.Port",f.edges().get(0).targetType());
    }
    @Test
    void deletedNonJavaIssueTargetKeepsItsReadableBeforeSide() {
        when(repo.diff(P,SHA,"settings.yml")).thenReturn(new RepositorySnapshots.DiffSides(
            SHA,BASE,"settings.yml",null,"removed","",RepositorySnapshots.Side.present("enabled: true\n"),
            RepositorySnapshots.Side.missing("REMOVED")));
        var prepared = new MaterialPreparationService(repo,store,parser).prepare(new PrepareCommand(
            P,"ISSUE","DIFF","main","settings.yml",null,null,SHA,BASE));
        assertFalse(prepared.failed(),prepared.errorCode()+":"+prepared.errorMessage());
        assertEquals(Side.BEFORE,prepared.targets().get(0).side());
    }
    @Test
    void deletedBrokenJavaIssueTargetFallsBackToOriginalText() {
        when(repo.diff(P,SHA,"Broken.java")).thenReturn(new RepositorySnapshots.DiffSides(
            SHA,BASE,"Broken.java",null,"removed","",RepositorySnapshots.Side.present("class Broken { void f( }"),
            RepositorySnapshots.Side.missing("REMOVED")));
        var prepared = new MaterialPreparationService(repo,store,parser).prepare(new PrepareCommand(
            P,"ISSUE","DIFF","main","Broken.java",null,null,SHA,BASE));
        assertFalse(prepared.failed(),prepared.errorCode()+":"+prepared.errorMessage());
    }
    @Test
    void constructorsContributeSignatureDependencies() {
        files(Map.of("app/Port.java","package app; interface Port {}"));
        var f = facts("package app; class Child { Child(Port port) {} }");
        assertEquals(1,f.resolvedOutgoingCount());
        assertEquals(1,f.declarationRelationCounts().get("SIGNATURE"));
    }
    @Test
    void declarationSummaryDoesNotIncludeConstructorImplementation() {
        String code="package app;\nclass Parent {\n Parent() {\n  String secret = \"IMPLEMENTATION\";\n }\n}\n";
        var type=parser.parse("app/Parent.java",code).types().get(0);
        var summary=new DeclarationSummaryBuilder().build(code,type);
        assertFalse(summary.content().contains("IMPLEMENTATION"),summary.content());
    }
    @Test
    void compactMethodRedactionKeepsAdjacentDeclarations() {
        String code="package app;\nclass Parent { void run() { int body = 1; } int requiredField; }\n";
        var summary=new DeclarationSummaryBuilder().build(code,parser.parse("app/Parent.java",code).types().get(0));
        assertTrue(summary.content().contains("requiredField"),summary.content());
        assertTrue(summary.content().contains("class Parent"));
    }
    @Test
    void genericAndArrayBaseTypesKeepTheirActualRelations() {
        files(Map.of("app/Parent.java","package app; class Parent<T> {}",
            "app/Port.java","package app; class Port {}"));
        var f=facts("package app; class Child extends Parent<Port> { Port[] ports; }");
        assertEquals(1,f.declarationRelationCounts().get("INHERITANCE"),f.toString());
        assertEquals(1,f.declarationRelationCounts().get("FIELD"),f.toString());
    }
    @Test
    void strictParserRejectsNumericText() {
        assertThrows(ApiException.class,()->new SpecialistResultParser().parse(
            "{\"summary\":123,\"issues\":[],\"limitations\":[]}","ISSUE"));
    }
    @Test
    void strictParserRejectsFractionalLineNumbers() {
        String json="""
            {"summary":"s","issues":[{"category":"CORRECTNESS","severity":"LOW","title":"t",
            "locations":[{"materialId":"target","startLine":1.9,"endLine":1.9}],
            "reason":"r","suggestion":"s","evidence":[],"changeRelation":null}],"limitations":[]}
            """;
        var target=target("package app; class Child {}\n");
        assertThrows(ApiException.class,()-> {
            var result=new SpecialistResultParser().parse(json,"ISSUE");
            new SpecialistResultValidator().validate(result,new SpecialistPromptBuilder().context(target,null));
        });
    }
    @Test
    void exhaustedAuxiliaryFileBudgetStopsReadingLaterBranches() {
        files(Map.of("app/A.java","package app; interface A {}","app/B.java","package app; interface B {}",
            "app/C.java","package app; interface C {}"));
        var properties=new SpecialistProperties();
        properties.setMaxAuxiliaryFiles(1);
        properties.setIncludeDirectDeclarations(false);
        new DeclarationEnhancementService(store,repo,parser,new DeclarationSummaryBuilder(),properties)
            .enhance(target("package app; class Child implements A,B,C {}"));
        verify(repo,never()).readFile(P,SHA,"app/C.java");
    }
    @Test
    void couplingPromptRetainsMissingCoverageDiagnostics() {
        files(Map.of());
        var t=target("package app; class Child { void run() { new vendor.Missing(); } }");
        t=new DeclarationEnhancementService(store,repo,parser,new DeclarationSummaryBuilder(),new SpecialistProperties()).enhance(t);
        assertTrue(t.gaps().isEmpty());
        var f=new CouplingFactsBuilder().build(t,new DeclarationResolver(P,SHA,repo,parser));
        assertEquals(0.0,f.coverage());
        var prompt=new PromptAssemblyService().assemble(new SpecialistPromptBuilder().build(t,f,null,null));
        assertTrue(prompt.userPrompt().contains("不能把本结果解释为零依赖"),prompt.userPrompt());
        assertTrue(prompt.userPrompt().contains("coverage=0.0"));
        assertTrue(prompt.userPrompt().contains("unresolvedTypeCount=1"));
    }

    @Test
    void zeroAuxiliaryBudgetDoesNotReadSource() {
        files(Map.of("app/Parent.java", "package app; class Parent {}"));
        var properties = new SpecialistProperties();
        properties.setMaxAuxiliaryFiles(0);
        var enhanced = new DeclarationEnhancementService(store, repo, parser,
                new DeclarationSummaryBuilder(), properties)
                .enhance(target("package app; class Child extends Parent {}"));
        assertTrue(enhanced.auxiliary().isEmpty());
        assertTrue(enhanced.gaps().stream().anyMatch(gap -> gap.code().equals("LIMIT_REACHED")));
        verify(repo, never()).readFile(anyString(), anyString(), anyString());
    }

    @Test
    void byteBudgetCountsFullUtf8SourceBeforeSummarizing() {
        String parent = "package app; class Parent { void run() { String value = \""
                + "汉".repeat(50) + "\"; } }";
        files(Map.of("app/Parent.java", parent, "app/Api.java", "package app; interface Api {}"));
        var properties = new SpecialistProperties();
        properties.setMaxAuxiliaryBytes(parent.length() + 1);
        properties.setIncludeDirectDeclarations(false);
        var enhanced = new DeclarationEnhancementService(store, repo, parser,
                new DeclarationSummaryBuilder(), properties)
                .enhance(target("package app; class Child extends Parent implements Api {}"));
        assertTrue(enhanced.auxiliary().isEmpty());
        assertTrue(enhanced.gaps().stream().anyMatch(gap -> gap.code().equals("LIMIT_REACHED")));
        verify(repo, never()).readFile(P, SHA, "app/Api.java");
    }

    @Test
    void oversizedRepositoryFailureAlsoStopsFurtherReads() {
        files(Map.of("app/Parent.java", "package app; class Parent {}",
                "app/Api.java", "package app; interface Api {}"));
        when(repo.readFile(P, SHA, "app/Parent.java")).thenThrow(new ApiException(
                ErrorCode.FILE_NOT_READABLE, "too large", Map.of("byteSize", 5000000)));
        var enhanced = new DeclarationEnhancementService(store, repo, parser,
                new DeclarationSummaryBuilder(), new SpecialistProperties())
                .enhance(target("package app; class Child extends Parent implements Api {}"));
        assertTrue(enhanced.gaps().stream().anyMatch(gap -> gap.code().equals("LIMIT_REACHED")));
        verify(repo, never()).readFile(P, SHA, "app/Api.java");
    }

    @Test
    void diffSidesShareTheSameAuxiliaryReadBudget() {
        String parent = "package app; class Parent {}";
        files(Map.of("app/Parent.java", parent));
        when(repo.listPaths(P, BASE)).thenReturn(new RepositorySnapshots.PathList(
                List.of(new RepositorySnapshots.PathEntry("app/Parent.java", "blob", true)), BASE, false));
        var t = target("package app; class Child extends Parent {}");
        var file = t.targets().get(0);
        Material after = new Material("after", P, SHA, file.path(), Side.AFTER, 1, 1,
                file.content(), file.contentHash(), "COMPLETE", null, Role.TARGET, Status.RESOLVED,
                null, file.structure());
        Material before = new Material("before", P, BASE, file.path(), Side.BEFORE, 1, 1,
                file.content(), file.contentHash(), "COMPLETE", null, Role.TARGET, Status.RESOLVED,
                null, file.structure());
        var diff = new PreparedTarget(P, "ISSUE", "DIFF", "main", file.path(), null, SHA, BASE,
                null, false, false, List.of(after, before), List.of(), List.of(), null, null);
        var properties = new SpecialistProperties();
        properties.setMaxAuxiliaryFiles(1);
        var enhanced = new DeclarationEnhancementService(store, repo, parser,
                new DeclarationSummaryBuilder(), properties).enhance(diff);
        assertEquals(1, enhanced.auxiliary().size());
        assertTrue(enhanced.gaps().stream().anyMatch(gap -> gap.code().equals("LIMIT_REACHED")));
        verify(repo, never()).readFile(P, BASE, "app/Parent.java");
    }

    @Test
    void nestedDeclarationsShareOneReadAndOneFileSlot() {
        files(Map.of("app/Outer.java", "package app; class Outer { static class A {} static class B {} }"));
        var properties = new SpecialistProperties();
        properties.setMaxAuxiliaryFiles(1);
        var enhanced = new DeclarationEnhancementService(store, repo, parser,
                new DeclarationSummaryBuilder(), properties)
                .enhance(target("package app; class Child { Outer.A a; Outer.B b; }"));
        assertEquals(List.of("app.Outer.A", "app.Outer.B"), enhanced.auxiliary().stream()
                .map(Material::declaredType).toList());
        verify(repo, times(1)).readFile(P, SHA, "app/Outer.java");
    }

    @Test
    void incompleteCandidateSearchCannotClaimAUniqueDeclaration() {
        files(Map.of("one/src/main/java/app/Parent.java", "package app; class Parent {}",
                "two/src/main/java/app/Parent.java", "package app; class Parent {}"));
        var properties = new SpecialistProperties();
        properties.setMaxAuxiliaryFiles(1);
        var enhanced = new DeclarationEnhancementService(store, repo, parser,
                new DeclarationSummaryBuilder(), properties)
                .enhance(target("package app; class Child extends Parent {}"));
        assertTrue(enhanced.auxiliary().isEmpty());
        assertTrue(enhanced.gaps().stream().anyMatch(gap -> gap.code().equals("LIMIT_REACHED")));
        verify(repo, times(1)).readFile(eq(P), eq(SHA), anyString());
    }

    @Test
    void constructorParameterDeclarationsAreEnhanced() {
        files(Map.of("app/Port.java", "package app; interface Port {}"));
        var enhanced = new DeclarationEnhancementService(store, repo, parser,
                new DeclarationSummaryBuilder(), new SpecialistProperties())
                .enhance(target("package app; class Child { Child(Port port) {} }"));
        assertEquals(List.of("app.Port"), enhanced.auxiliary().stream().map(Material::declaredType).toList());
    }

    @Test
    void summaryPreservesLinesAndNestedDeclarationsWithTabsAndCrLf() {
        String code = "package app;\r\nrecord Parent(int value) {\r\n"
                + "\tParent { System.out.println(\"compact-body\"); }\r\n"
                + "\tstatic { System.out.println(\"static-body\"); }\r\n"
                + "\tstatic class Inner { int field; void run() { System.out.println(\"nested-body\"); } }\r\n}\r\n";
        var type = parser.parse("app/Parent.java", code).types().get(0);
        var summary = new DeclarationSummaryBuilder().build(code, type);
        assertTrue(summary.content().contains("record Parent(int value)"));
        assertTrue(summary.content().contains("int field"));
        assertTrue(summary.content().contains("void run()"));
        assertFalse(summary.content().contains("compact-body"));
        assertFalse(summary.content().contains("static-body"));
        assertFalse(summary.content().contains("nested-body"));
        assertEquals(summary.endLine() - summary.startLine() + 1,
                JavaStructureParser.countLines(summary.content()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"1\"", "true", "null"})
    void strictParserRejectsNonIntegerLineTypes(String line) {
        String json = """
                {"summary":"s","issues":[{"category":"CORRECTNESS","severity":"LOW","title":"t",
                "locations":[{"materialId":"target","startLine":%s,"endLine":%s}],
                "reason":"r","suggestion":"s","evidence":[],"changeRelation":null}],"limitations":[]}
                """.formatted(line, line);
        assertThrows(ApiException.class, () -> new SpecialistResultParser().parse(json, "ISSUE"));
    }

    @Test
    void internalQualifiedAndNestedReferencesRemainInternal() {
        files(Map.of());
        var source = parser.parse("app/Child.java", "package app; class Child { static class Port {} }");
        var resolver = new DeclarationResolver(P, SHA, repo, parser);
        for (String reference : List.of("Port", "Child.Port", "app.Child.Port")) {
            assertEquals(Status.INTERNAL, resolver.resolve(reference, source).status(), reference);
        }
        verify(repo, never()).readFile(anyString(), anyString(), anyString());
    }

    @Test
    void fullyQualifiedPlatformTypesDoNotBecomeUnresolvedDependencies() {
        files(Map.of("app/Port.java", "package app; class Port {}"));
        var f = facts("package app; class Child { java.util.List<Port> ports; }");
        assertEquals(1, f.resolvedOutgoingCount());
        assertEquals(0, f.unresolvedTypeCount());
        assertEquals(1, f.declarationRelationCounts().get("GENERIC"));
    }

    @Test
    void summaryRedactsMultipleAndOverlappingBodiesWithoutLosingDeclarations() {
        String code = "package app;\nclass Parent { int first; void run() { class Local { void nested() {"
                + " System.out.println(\"nested-implementation\"); } } } int middle;"
                + " void other() { System.out.println(\"other-implementation\"); } int last; }\n";
        var summary = new DeclarationSummaryBuilder().build(code, parser.parse("app/Parent.java", code).types().get(0));
        assertTrue(summary.content().contains("int first"));
        assertTrue(summary.content().contains("int middle"));
        assertTrue(summary.content().contains("int last"));
        assertTrue(summary.content().contains("void run()"));
        assertTrue(summary.content().contains("void other()"));
        assertFalse(summary.content().contains("implementation"));
        assertEquals(1, JavaStructureParser.countLines(summary.content()));
    }
}
