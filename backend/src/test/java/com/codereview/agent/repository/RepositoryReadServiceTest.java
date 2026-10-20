package com.codereview.agent.repository;

import com.codereview.agent.api.ApiException;
import com.codereview.agent.api.ErrorCode;
import com.codereview.agent.db.AgentDatabase;
import com.codereview.agent.entity.AgentProject;
import com.codereview.agent.mapper.AgentProjectMapper;
import com.codereview.git.ChangedFile;
import com.codereview.git.CommitDetail;
import com.codereview.git.GitHostClient;
import com.codereview.git.GitHostClientRegistry;
import com.codereview.git.GitTreeEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RepositoryReadServiceTest {

    private static final String PROJECT_ID = "01J9Z6Q3W4X5Y6Z7A8B9C0D1E2";
    private static final String HEAD = "b7a312e0c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6";
    private static final String PARENT = "12ac80f0a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6";

    @Mock
    private AgentDatabase db;
    @Mock
    private AgentProjectMapper projectMapper;
    @Mock
    private GitHostClientRegistry registry;
    @Mock
    private GitHostClient client;

    private RepositoryReadService service;
    private AgentProject project;

    @BeforeEach
    void setUp() {
        service = new RepositoryReadService(db, registry);
        project = new AgentProject();
        project.setId(PROJECT_ID);
        project.setRepoUrl("https://github.com/acme/order-service");
        project.setCredentialRef("token");
        project.setCredentialType(1);
        project.setRevision(3);
        when(db.mapper(AgentProjectMapper.class)).thenReturn(projectMapper);
        when(projectMapper.selectById(any())).thenReturn(project);
        when(registry.forRepo(any())).thenReturn(client);
    }

    @Test
    void cachesWithinTheSameProjectRevisionAndCommit() {
        when(client.rawFile(any(), any(), any(), eq(HEAD), eq("src/Order.java")))
                .thenReturn("class Order {}\n");

        service.readFile(PROJECT_ID, HEAD, "src/Order.java");
        RepositorySnapshots.FileSnapshot snapshot = service.readFile(PROJECT_ID, HEAD, "src/Order.java");

        assertEquals("class Order {}\n", snapshot.content());
        assertEquals(1, snapshot.lineCount());
        verify(client, times(1)).rawFile(any(), any(), any(), eq(HEAD), eq("src/Order.java"));
    }

    @Test
    void differentCommitsAreNeverServedFromTheSameEntry() {
        when(client.rawFile(any(), any(), any(), eq(HEAD), eq("A.java"))).thenReturn("new");
        when(client.rawFile(any(), any(), any(), eq(PARENT), eq("A.java"))).thenReturn("old");

        assertEquals("new", service.readFile(PROJECT_ID, HEAD, "A.java").content());
        assertEquals("old", service.readFile(PROJECT_ID, PARENT, "A.java").content());
    }

    @Test
    void replacingTheTokenInvalidatesTheCacheNamespace() {
        project.setRevision(4);
        when(client.rawFile(any(), any(), any(), eq(HEAD), eq("A.java"))).thenReturn("content");
        service.readFile(PROJECT_ID, HEAD, "A.java");

        AgentProject updated = new AgentProject();
        updated.setId(PROJECT_ID);
        updated.setRepoUrl(project.getRepoUrl());
        updated.setCredentialRef("new-token");
        updated.setCredentialType(1);
        updated.setRevision(5);
        when(projectMapper.selectById(any())).thenReturn(updated);

        service.readFile(PROJECT_ID, HEAD, "A.java");

        verify(client, times(2)).rawFile(any(), any(), any(), eq(HEAD), eq("A.java"));
    }

    @Test
    void refusesShortShaAndUnsafePathsBeforeCallingTheHost() {
        ApiException shortSha = assertThrows(ApiException.class,
                () -> service.readFile(PROJECT_ID, "b7a312e", "A.java"));
        assertEquals(ErrorCode.VALIDATION_FAILED, shortSha.errorCode());

        ApiException traversal = assertThrows(ApiException.class,
                () -> service.readFile(PROJECT_ID, HEAD, "../secret"));
        assertEquals(ErrorCode.VALIDATION_FAILED, traversal.errorCode());

        verify(client, never()).rawFile(any(), any(), any(), any(), any());
    }

    @Test
    void binaryAndOversizedFilesAreRejectedInsteadOfTruncated() {
        when(client.rawFile(any(), any(), any(), eq(HEAD), eq("image.bin"))).thenReturn("a\u0000b");
        ApiException binary = assertThrows(ApiException.class,
                () -> service.readFile(PROJECT_ID, HEAD, "image.bin"));
        assertEquals(ErrorCode.FILE_NOT_READABLE, binary.errorCode());

        when(client.rawFile(any(), any(), any(), eq(HEAD), eq("huge.txt")))
                .thenReturn("x".repeat(RepositoryReadService.MAX_TARGET_BYTES + 1));
        ApiException huge = assertThrows(ApiException.class,
                () -> service.readFile(PROJECT_ID, HEAD, "huge.txt"));
        assertEquals(ErrorCode.FILE_NOT_READABLE, huge.errorCode());
    }

    @Test
    void listsPathsSortedAndMarksReviewableFiles() {
        when(client.tree(any(), any(), any(), eq(HEAD))).thenReturn(List.of(
                new GitTreeEntry("src/b/B.java", "blob"),
                new GitTreeEntry("src", "tree"),
                new GitTreeEntry("README.md", "blob"),
                new GitTreeEntry("photo.png", "blob")));

        RepositorySnapshots.PathList list = service.listPaths(PROJECT_ID, HEAD);

        assertEquals(HEAD, list.sha());
        assertEquals(List.of("README.md", "photo.png", "src", "src/b/B.java"),
                list.entries().stream().map(RepositorySnapshots.PathEntry::path).toList());
        assertTrue(list.entries().stream().anyMatch(e -> e.path().equals("src/b/B.java") && e.reviewable()));
        assertFalse(list.entries().stream().anyMatch(e -> e.path().equals("src") && e.reviewable()));
    }

    @Test
    void readsAnInclusiveLineRangeAndReportsMoreContent() {
        when(client.rawFile(any(), any(), any(), eq(HEAD), eq("A.java")))
                .thenReturn("one\ntwo\nthree\nfour\n");

        RepositorySnapshots.RangeSnapshot range = service.readRange(PROJECT_ID, HEAD, "A.java", 2, 3);

        assertEquals("two\nthree", range.content());
        assertEquals(3, range.endLine());
        assertTrue(range.hasMore());
    }

    @Test
    void diffUsesTheFirstParentForTheBeforeSide() {
        when(client.commitDetail(any(), any(), any(), eq(HEAD))).thenReturn(new CommitDetail(
                HEAD, List.of(PARENT), "msg", "a", "2026-10-20", 1, 1, 2, false,
                List.of(new ChangedFile("src/Order.java", null, ChangedFile.MODIFIED, 1, 1, 2, "@@"))));
        when(client.rawFile(any(), any(), any(), eq(HEAD), eq("src/Order.java"))).thenReturn("after\n");
        when(client.rawFile(any(), any(), any(), eq(PARENT), eq("src/Order.java"))).thenReturn("before\n");

        RepositorySnapshots.DiffSides sides = service.diff(PROJECT_ID, HEAD, "src/Order.java");

        assertEquals("before\n", sides.before());
        assertEquals("after\n", sides.after());
        assertEquals(PARENT, sides.baseSha());
        assertFalse(sides.beforeMissing());
        assertEquals("@@", sides.patch());
    }

    @Test
    void diffMarksAMissingBeforeSideWhenTheParentCannotBeRead() {
        when(client.commitDetail(any(), any(), any(), eq(HEAD))).thenReturn(new CommitDetail(
                HEAD, List.of(PARENT), "msg", "a", "2026-10-20", 1, 0, 1, false,
                List.of(new ChangedFile("src/Order.java", null, ChangedFile.MODIFIED, 1, 0, 1, "@@"))));
        when(client.rawFile(any(), any(), any(), eq(HEAD), eq("src/Order.java"))).thenReturn("after\n");
        when(client.rawFile(any(), any(), any(), eq(PARENT), eq("src/Order.java")))
                .thenThrow(new com.codereview.common.BusinessException(
                        com.codereview.common.ResultCode.GIT_FILE_NOT_VIEWABLE.getCode(), "父版本不可读"));

        RepositorySnapshots.DiffSides sides = service.diff(PROJECT_ID, HEAD, "src/Order.java");

        assertTrue(sides.beforeMissing());
        assertNull(sides.before());
        assertEquals("after\n", sides.after());
    }

    @Test
    void addedFileHasNoBeforeSideAndRootCommitIsExplicit() {
        when(client.commitDetail(any(), any(), any(), eq(HEAD))).thenReturn(new CommitDetail(
                HEAD, List.of(), "root", "a", "2026-10-20", 3, 0, 3, false,
                List.of(new ChangedFile("src/New.java", null, ChangedFile.ADDED, 3, 0, 3, "@@"))));
        when(client.rawFile(any(), any(), any(), eq(HEAD), eq("src/New.java"))).thenReturn("new\n");

        RepositorySnapshots.DiffSides sides = service.diff(PROJECT_ID, HEAD, "src/New.java");

        assertNull(sides.before());
        assertFalse(sides.beforeMissing());
        assertEquals("new\n", sides.after());
    }

    @Test
    void missingFileInCommitIsReportedAsNotFound() {
        when(client.commitDetail(any(), any(), any(), eq(HEAD))).thenReturn(new CommitDetail(
                HEAD, List.of(PARENT), "msg", "a", "2026-10-20", 0, 0, 0, false, List.of()));

        ApiException error = assertThrows(ApiException.class,
                () -> service.diff(PROJECT_ID, HEAD, "src/Other.java"));

        assertEquals(ErrorCode.NOT_FOUND, error.errorCode());
    }
}
