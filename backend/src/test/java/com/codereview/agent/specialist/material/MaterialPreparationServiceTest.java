package com.codereview.agent.specialist.material;

import com.codereview.agent.api.ApiException;
import com.codereview.agent.api.ErrorCode;
import com.codereview.agent.repository.RepositoryReadService;
import com.codereview.agent.repository.RepositorySnapshots;
import com.codereview.agent.support.SpecialistTypes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MaterialPreparationServiceTest {

    private static final String PROJECT = "01J9Z6Q3W4X5Y6Z7A8B9C0D1E2";
    private static final String HEAD = "b7a312e0c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6";
    private static final String BASE = "12ac80f0a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6";
    private static final String CODE = "package a.b;\n\npublic class A {\n    void run() {\n    }\n}\n";

    private RepositoryReadService repository;
    private MaterialStore store;
    private MaterialPreparationService service;

    @BeforeEach
    void setUp() {
        repository = mock(RepositoryReadService.class);
        store = mock(MaterialStore.class);
        when(store.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        service = new MaterialPreparationService(repository, store, new JavaStructureParser());
    }

    @Test
    void preparesWholeFileWithStructureAndOriginalLines() {
        when(repository.readFile(PROJECT, HEAD, "src/A.java"))
                .thenReturn(new RepositorySnapshots.FileSnapshot(HEAD, "src/A.java", CODE,
                        "hash-1", 60, 6));

        SpecialistMaterials.PreparedTarget target = service.prepare(command(SpecialistTypes.ISSUE,
                "FILES", "src/A.java", null));

        assertFalse(target.failed());
        assertEquals(1, target.targets().size());
        SpecialistMaterials.Material material = target.targets().get(0);
        assertEquals(HEAD, material.sha());
        assertEquals(SpecialistMaterials.Side.FILE, material.side());
        assertEquals(SpecialistMaterials.Role.TARGET, material.role());
        assertEquals(1, material.startLine());
        assertEquals(6, material.endLine());
        assertEquals("COMPLETE", material.completeness());
        assertNotNull(material.structure());
        assertEquals("a.b.A", material.structure().topLevelType().orElseThrow().qualifiedName());
        assertTrue(target.gaps().isEmpty(), () -> "不应有缺口：" + target.gaps());
        verify(store).save(material);
    }

    @Test
    void readFailureFailsTheFileInsteadOfReturningEmptySuccess() {
        when(repository.readFile(PROJECT, HEAD, "src/A.java"))
                .thenThrow(new ApiException(ErrorCode.FILE_NOT_READABLE, "too large",
                        java.util.Map.of("reason", "TOO_LARGE")));

        SpecialistMaterials.PreparedTarget target = service.prepare(command(SpecialistTypes.ISSUE,
                "FILES", "src/A.java", null));

        assertTrue(target.failed());
        assertEquals(ErrorCode.FILE_NOT_READABLE.code(), target.errorCode());
        assertTrue(target.targets().isEmpty());
        verify(store, never()).save(any());
    }

    @Test
    void issueAnalysisKeepsOriginalTextWhenStructureParsingFails() {
        when(repository.readFile(PROJECT, HEAD, "src/A.java"))
                .thenReturn(new RepositorySnapshots.FileSnapshot(HEAD, "src/A.java",
                        "class A { void x( }", "hash-2", 20, 1));

        SpecialistMaterials.PreparedTarget target = service.prepare(command(SpecialistTypes.ISSUE,
                "FILES", "src/A.java", null));

        assertFalse(target.failed());
        assertEquals(1, target.targets().size());
        assertNull(target.targets().get(0).structure());
        assertTrue(target.gaps().stream()
                .anyMatch(gap -> gap.code().equals(SpecialistMaterials.Status.PARSE_FAILED.name())));
    }

    @Test
    void couplingCannotContinueWithoutTargetAst() {
        when(repository.readFile(PROJECT, HEAD, "src/A.java"))
                .thenReturn(new RepositorySnapshots.FileSnapshot(HEAD, "src/A.java",
                        "class A { void x( }", "hash-2", 20, 1));

        SpecialistMaterials.PreparedTarget target = service.prepare(command(SpecialistTypes.COUPLING,
                "FILES", "src/A.java", null));

        assertTrue(target.failed());
        assertEquals(ErrorCode.STRUCTURE_PARSE_FAILED.code(), target.errorCode());
        verify(store, never()).save(any());
    }

    @Test
    void nonJavaCouplingIsRejectedButIssueKeepsText() {
        when(repository.readFile(PROJECT, HEAD, "src/README.md"))
                .thenReturn(new RepositorySnapshots.FileSnapshot(HEAD, "src/README.md", "# hi\n",
                        "hash-3", 5, 1));

        SpecialistMaterials.PreparedTarget issue = service.prepare(command(SpecialistTypes.ISSUE,
                "FILES", "src/README.md", null));
        assertFalse(issue.failed());
        assertTrue(issue.gaps().stream()
                .anyMatch(gap -> gap.code().equals(SpecialistMaterials.Status.UNSUPPORTED.name())));

        SpecialistMaterials.PreparedTarget coupling = service.prepare(command(SpecialistTypes.COUPLING,
                "FILES", "src/README.md", null));
        assertTrue(coupling.failed());
        assertEquals(ErrorCode.UNSUPPORTED_LANGUAGE.code(), coupling.errorCode());
    }

    @Test
    void addedFileHasOnlyAfterSideAndNoFakeBeforeMaterial() {
        when(repository.diff(PROJECT, HEAD, "src/New.java")).thenReturn(new RepositorySnapshots.DiffSides(
                HEAD, null, "src/New.java", null, "added", "patch",
                RepositorySnapshots.Side.missing("ADDED"),
                RepositorySnapshots.Side.present("package a.b;\nclass New {}\n")));

        SpecialistMaterials.PreparedTarget target = service.prepare(command(SpecialistTypes.ISSUE,
                "DIFF", "src/New.java", null));

        assertFalse(target.failed());
        assertTrue(target.added());
        assertFalse(target.removed());
        assertEquals(1, target.targets().size());
        assertEquals(SpecialistMaterials.Side.AFTER, target.targets().get(0).side());
        assertEquals(HEAD, target.targets().get(0).sha());
    }

    @Test
    void removedFileHasOnlyBeforeSideAtBaseSha() {
        when(repository.diff(PROJECT, HEAD, "src/Old.java")).thenReturn(new RepositorySnapshots.DiffSides(
                HEAD, BASE, "src/Old.java", null, "removed", "patch",
                RepositorySnapshots.Side.present("package a.b;\nclass Old {}\n"),
                RepositorySnapshots.Side.missing("REMOVED")));

        SpecialistMaterials.PreparedTarget target = service.prepare(command(SpecialistTypes.ISSUE,
                "DIFF", "src/Old.java", null));

        assertFalse(target.failed());
        assertTrue(target.removed());
        assertEquals(1, target.targets().size());
        assertEquals(SpecialistMaterials.Side.BEFORE, target.targets().get(0).side());
        assertEquals(BASE, target.targets().get(0).sha());
        assertEquals(BASE, target.baseSha());
    }

    @Test
    void renameKeepsOldPathOnBeforeSide() {
        when(repository.diff(PROJECT, HEAD, "src/NewName.java"))
                .thenReturn(new RepositorySnapshots.DiffSides(HEAD, BASE, "src/NewName.java",
                        "src/OldName.java", "renamed", "patch",
                        RepositorySnapshots.Side.present("package a.b;\nclass OldName {}\n"),
                        RepositorySnapshots.Side.present("package a.b;\nclass NewName {}\n")));

        SpecialistMaterials.PreparedTarget target = service.prepare(command(SpecialistTypes.ISSUE,
                "DIFF", "src/NewName.java", null));

        assertFalse(target.failed());
        assertEquals("src/OldName.java", target.oldPath());
        assertEquals("src/OldName.java", target.targets().stream()
                .filter(material -> material.side() == SpecialistMaterials.Side.BEFORE)
                .findFirst().orElseThrow().path());
        assertEquals("src/NewName.java", target.targets().stream()
                .filter(material -> material.side() == SpecialistMaterials.Side.AFTER)
                .findFirst().orElseThrow().path());
    }

    @Test
    void binaryDiffIsNotReadable() {
        when(repository.diff(PROJECT, HEAD, "src/Logo.png"))
                .thenReturn(new RepositorySnapshots.DiffSides(HEAD, BASE, "src/Logo.png", null,
                        "modified", null, RepositorySnapshots.Side.unreadable("BINARY"),
                        RepositorySnapshots.Side.unreadable("BINARY")));

        SpecialistMaterials.PreparedTarget target = service.prepare(command(SpecialistTypes.ISSUE,
                "DIFF", "src/Logo.png", null));

        assertTrue(target.failed());
        assertEquals(ErrorCode.FILE_NOT_READABLE.code(), target.errorCode());
    }

    @Test
    void unreadableSideIsARecordedGapWhileTheOtherSideContinues() {
        when(repository.diff(PROJECT, HEAD, "src/A.java"))
                .thenReturn(new RepositorySnapshots.DiffSides(HEAD, BASE, "src/A.java", null,
                        "modified", "patch", RepositorySnapshots.Side.unreadable("READ_FAILED"),
                        RepositorySnapshots.Side.present(CODE)));

        SpecialistMaterials.PreparedTarget target = service.prepare(command(SpecialistTypes.ISSUE,
                "DIFF", "src/A.java", null));

        assertFalse(target.failed());
        assertEquals(1, target.targets().size());
        assertEquals(SpecialistMaterials.Side.AFTER, target.targets().get(0).side());
        assertTrue(target.gaps().stream().anyMatch(gap -> gap.detail().contains("READ_FAILED")));
    }

    @Test
    void rootCommitRecordsMissingBaseWithoutFailing() {
        when(repository.diff(PROJECT, HEAD, "src/A.java"))
                .thenReturn(new RepositorySnapshots.DiffSides(HEAD, BASE, "src/A.java", null,
                        "modified", "patch", RepositorySnapshots.Side.missing("ROOT_COMMIT"),
                        RepositorySnapshots.Side.present(CODE)));

        SpecialistMaterials.PreparedTarget target = service.prepare(command(SpecialistTypes.ISSUE,
                "DIFF", "src/A.java", null));

        assertFalse(target.failed());
        assertTrue(target.gaps().stream().anyMatch(gap -> gap.code().equals("BASE_UNAVAILABLE")));
    }

    @Test
    void rejectsShortShaAndNonFixedSourceBeforeCallingTheRepository() {
        ApiException failure = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> service.prepare(new SpecialistMaterials.PrepareCommand(PROJECT,
                        SpecialistTypes.ISSUE, "FILES", "main", "src/A.java", null, null,
                        "b7a312e", null)));
        assertEquals(ErrorCode.VALIDATION_FAILED, failure.errorCode());
        verify(repository, never()).readFile(any(), any(), any());
    }

    @Test
    void duplicatePreparationReusesStoredMaterialId() {
        when(repository.readFile(PROJECT, HEAD, "src/A.java"))
                .thenReturn(new RepositorySnapshots.FileSnapshot(HEAD, "src/A.java", CODE,
                        "hash-1", 60, 6));
        when(store.save(any())).thenAnswer(invocation -> {
            SpecialistMaterials.Material material = invocation.getArgument(0);
            return material.withId("01J9Z6Q3W4X5Y6Z7A8B9C0D1E3");
        });

        SpecialistMaterials.PreparedTarget target = service.prepare(command(SpecialistTypes.ISSUE,
                "FILES", "src/A.java", null));

        assertEquals("01J9Z6Q3W4X5Y6Z7A8B9C0D1E3", target.targets().get(0).id());
    }

    private static SpecialistMaterials.PrepareCommand command(String type, String sourceKind,
                                                              String path, String oldPath) {
        return new SpecialistMaterials.PrepareCommand(PROJECT, type, sourceKind, "main", path,
                oldPath, "说明", HEAD, "DIFF".equals(sourceKind) ? BASE : null);
    }
}
