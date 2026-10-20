package com.codereview.agent.repository;

import com.codereview.agent.api.ApiException;
import com.codereview.agent.api.ErrorCode;
import com.codereview.agent.db.AgentDatabase;
import com.codereview.agent.entity.AgentProject;
import com.codereview.agent.mapper.AgentProjectMapper;
import com.codereview.common.BusinessException;
import com.codereview.common.ResultCode;
import com.codereview.git.ChangedFile;
import com.codereview.git.CommitDetail;
import com.codereview.git.GitHostClient;
import com.codereview.git.GitHostClientRegistry;
import com.codereview.git.GitTree;
import com.codereview.git.GitTreeEntry;
import com.codereview.git.HostHttpException;
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
        project.setCredential("token");
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
    void replacingTheTokenInvalidatesTheOuterCacheNamespace() {
        project.setRevision(4);
        when(client.rawFile(any(), any(), any(), eq(HEAD), eq("A.java"))).thenReturn("content");
        service.readFile(PROJECT_ID, HEAD, "A.java");

        AgentProject updated = new AgentProject();
        updated.setId(PROJECT_ID);
        updated.setRepoUrl(project.getRepoUrl());
        updated.setCredential("new-token");
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
    void binaryAndOversizedFilesAreRejectedWithDistinctReasons() {
        when(client.rawFile(any(), any(), any(), eq(HEAD), eq("image.bin"))).thenReturn("a\u0000b");
        ApiException binary = assertThrows(ApiException.class,
                () -> service.readFile(PROJECT_ID, HEAD, "image.bin"));
        assertEquals(ErrorCode.FILE_NOT_READABLE, binary.errorCode());
        assertEquals("BINARY", ((java.util.Map<?, ?>) binary.details()).get("reason"));

        when(client.rawFile(any(), any(), any(), eq(HEAD), eq("huge.txt")))
                .thenReturn("x".repeat(RepositoryReadService.MAX_TARGET_BYTES + 1));
        ApiException huge = assertThrows(ApiException.class,
                () -> service.readFile(PROJECT_ID, HEAD, "huge.txt"));
        assertEquals(ErrorCode.FILE_NOT_READABLE, huge.errorCode());
        assertEquals("TOO_LARGE", ((java.util.Map<?, ?>) huge.details()).get("reason"));
    }

    @Test
    void listsPathsSortedAndCarriesHostTruncation() {
        when(client.treeWithMeta(any(), any(), any(), eq(HEAD))).thenReturn(new GitTree(List.of(
                new GitTreeEntry("src/b/B.java", "blob"),
                new GitTreeEntry("src", "tree"),
                new GitTreeEntry("README.md", "blob"),
                new GitTreeEntry("photo.png", "blob")), true));

        RepositorySnapshots.PathList list = service.listPaths(PROJECT_ID, HEAD);

        assertTrue(list.truncated(), "宿主明确截断时必须保留，不能标为完整");
        assertEquals(List.of("README.md", "photo.png", "src", "src/b/B.java"),
                list.entries().stream().map(RepositorySnapshots.PathEntry::path).toList());
        assertTrue(list.entries().stream().anyMatch(e -> e.path().equals("src/b/B.java") && e.reviewable()));
        assertFalse(list.entries().stream().anyMatch(e -> e.path().equals("src") && e.reviewable()));
    }

    @Test
    void readsAnInclusiveLineRangeAndKeepsLeadingBlankLines() {
        when(client.rawFile(any(), any(), any(), eq(HEAD), eq("A.java")))
                .thenReturn("\nalpha\nbeta\n");

        RepositorySnapshots.RangeSnapshot range = service.readRange(PROJECT_ID, HEAD, "A.java", 1, 2);

        assertEquals("\nalpha", range.content(), "首行空行不能被吞掉");
        assertEquals(2, range.endLine());
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

        assertEquals("before\n", sides.before().content());
        assertEquals("after\n", sides.after().content());
        assertEquals(PARENT, sides.baseSha());
        assertEquals("@@", sides.patch());
    }

    @Test
    void renameReadsAfterFromTheNewPathAndBeforeFromTheOldPath() {
        when(client.commitDetail(any(), any(), any(), eq(HEAD))).thenReturn(new CommitDetail(
                HEAD, List.of(PARENT), "rename", "a", "2026-10-20", 0, 0, 0, false,
                List.of(new ChangedFile("src/New.java", "src/Old.java", ChangedFile.RENAMED, 0, 0, 0, "@@"))));
        when(client.rawFile(any(), any(), any(), eq(HEAD), eq("src/New.java"))).thenReturn("new body\n");
        when(client.rawFile(any(), any(), any(), eq(PARENT), eq("src/Old.java"))).thenReturn("old body\n");

        RepositorySnapshots.DiffSides sides = service.diff(PROJECT_ID, HEAD, "src/Old.java");

        assertEquals("src/New.java", sides.path(), "返回的应是规范新路径");
        assertEquals("src/Old.java", sides.oldPath());
        assertEquals("new body\n", sides.after().content());
        assertEquals("old body\n", sides.before().content());
    }

    @Test
    void missingBeforeSideIsMarkedMissingRatherThanFailingTheWholeDiff() {
        when(client.commitDetail(any(), any(), any(), eq(HEAD))).thenReturn(new CommitDetail(
                HEAD, List.of(PARENT), "msg", "a", "2026-10-20", 1, 0, 1, false,
                List.of(new ChangedFile("src/Order.java", null, ChangedFile.MODIFIED, 1, 0, 1, "@@"))));
        when(client.rawFile(any(), any(), any(), eq(HEAD), eq("src/Order.java"))).thenReturn("after\n");
        when(client.rawFile(any(), any(), any(), eq(PARENT), eq("src/Order.java")))
                .thenThrow(new HostHttpException(404, "宿主返回 HTTP 404", null, null));

        RepositorySnapshots.DiffSides sides = service.diff(PROJECT_ID, HEAD, "src/Order.java");

        assertEquals(RepositorySnapshots.Side.MISSING, sides.before().status());
        assertEquals("NOT_AVAILABLE", sides.before().reason());
        assertEquals("after\n", sides.after().content());
    }

    @Test
    void unreadableAfterSideKeepsItsReasonAndTheBeforeSide() {
        when(client.commitDetail(any(), any(), any(), eq(HEAD))).thenReturn(new CommitDetail(
                HEAD, List.of(PARENT), "msg", "a", "2026-10-20", 1, 1, 1, false,
                List.of(new ChangedFile("src/Huge.java", null, ChangedFile.MODIFIED, 1, 1, 1, "@@"))));
        when(client.rawFile(any(), any(), any(), eq(HEAD), eq("src/Huge.java")))
                .thenReturn("x".repeat(RepositoryReadService.MAX_TARGET_BYTES + 1));
        when(client.rawFile(any(), any(), any(), eq(PARENT), eq("src/Huge.java"))).thenReturn("small\n");

        RepositorySnapshots.DiffSides sides = service.diff(PROJECT_ID, HEAD, "src/Huge.java");

        assertEquals(RepositorySnapshots.Side.UNREADABLE, sides.after().status());
        assertEquals("TOO_LARGE", sides.after().reason(), "超限不能被说成二进制");
        assertEquals("small\n", sides.before().content(), "另一侧仍应可读");
        assertFalse(sides.binary());
    }

    @Test
    void hostFailureOnBeforeSideKeepsAfterAndAllowsRereading() {
        for (int status : new int[]{HostHttpException.TIMEOUT, 401, 403, 429, 503}) {
            assertHostFailureKeepsOtherSide(status, true);
        }
    }

    @Test
    void hostFailureOnAfterSideKeepsBeforeAndAllowsRereading() {
        for (int status : new int[]{HostHttpException.TIMEOUT, 401, 403, 429, 503}) {
            assertHostFailureKeepsOtherSide(status, false);
        }
    }

    private void assertHostFailureKeepsOtherSide(int status, boolean beforeFails) {
        String path = "src/Status" + status + ".java";
        when(client.commitDetail(any(), any(), any(), eq(HEAD))).thenReturn(new CommitDetail(
                HEAD, List.of(PARENT), "msg", "a", "2026-10-20", 1, 1, 2, false,
                List.of(new ChangedFile(path, null, ChangedFile.MODIFIED, 1, 1, 2, "@@"))));
        String failedSha = beforeFails ? PARENT : HEAD;
        String readableSha = beforeFails ? HEAD : PARENT;
        when(client.rawFile(any(), any(), any(), eq(readableSha), eq(path))).thenReturn("readable\n");
        when(client.rawFile(any(), any(), any(), eq(failedSha), eq(path)))
                .thenThrow(new HostHttpException(status, "host failure", "30", null));

        RepositorySnapshots.DiffSides sides = service.diff(PROJECT_ID, HEAD, path);
        RepositorySnapshots.Side failed = beforeFails ? sides.before() : sides.after();
        RepositorySnapshots.Side readable = beforeFails ? sides.after() : sides.before();
        String reason = switch (status) {
            case HostHttpException.TIMEOUT -> "UPSTREAM_TIMEOUT";
            case 401, 403 -> "REPO_UNAUTHORIZED";
            case 429 -> "RATE_LIMITED";
            default -> "REPO_UNAVAILABLE";
        };
        assertEquals(RepositorySnapshots.Side.UNREADABLE, failed.status());
        assertEquals(reason, failed.reason());
        assertEquals("readable\n", readable.content());
        assertFalse(sides.binary());

        when(client.rawFile(any(), any(), any(), eq(failedSha), eq(path))).thenReturn("recovered\n");
        RepositorySnapshots.DiffSides retried = service.diff(PROJECT_ID, HEAD, path);
        RepositorySnapshots.Side recovered = beforeFails ? retried.before() : retried.after();
        assertEquals(RepositorySnapshots.Side.PRESENT, recovered.status());
        assertEquals("recovered\n", recovered.content(), "暂态失败不能被缓存成永久缺失");
    }

    @Test
    void addedFileHasNoBeforeSideAndRootCommitIsExplicit() {
        when(client.commitDetail(any(), any(), any(), eq(HEAD))).thenReturn(new CommitDetail(
                HEAD, List.of(), "root", "a", "2026-10-20", 3, 0, 3, false,
                List.of(new ChangedFile("src/New.java", null, ChangedFile.ADDED, 3, 0, 3, "@@"))));
        when(client.rawFile(any(), any(), any(), eq(HEAD), eq("src/New.java"))).thenReturn("new\n");

        RepositorySnapshots.DiffSides sides = service.diff(PROJECT_ID, HEAD, "src/New.java");

        assertEquals(RepositorySnapshots.Side.MISSING, sides.before().status());
        assertEquals("ADDED", sides.before().reason());
        assertEquals("new\n", sides.after().content());
    }

    @Test
    void removedFileHasAfterSideMarkedMissing() {
        when(client.commitDetail(any(), any(), any(), eq(HEAD))).thenReturn(new CommitDetail(
                HEAD, List.of(PARENT), "delete", "a", "2026-10-20", 0, 2, 2, false,
                List.of(new ChangedFile("src/Removed.java", null, ChangedFile.REMOVED, 0, 2, 2, "@@"))));
        when(client.rawFile(any(), any(), any(), eq(PARENT), eq("src/Removed.java"))).thenReturn("gone\n");

        RepositorySnapshots.DiffSides sides = service.diff(PROJECT_ID, HEAD, "src/Removed.java");

        assertEquals(RepositorySnapshots.Side.MISSING, sides.after().status());
        assertEquals("REMOVED", sides.after().reason());
        assertEquals("gone\n", sides.before().content());
    }

    @Test
    void missingFileInCommitIsReportedAsNotFound() {
        when(client.commitDetail(any(), any(), any(), eq(HEAD))).thenReturn(new CommitDetail(
                HEAD, List.of(PARENT), "msg", "a", "2026-10-20", 0, 0, 0, false, List.of()));

        ApiException error = assertThrows(ApiException.class,
                () -> service.diff(PROJECT_ID, HEAD, "src/Other.java"));

        assertEquals(ErrorCode.NOT_FOUND, error.errorCode());
    }

    @Test
    void truncatedCommitListNeverClaimsAFileIsAbsent() {
        when(client.commitDetail(any(), any(), any(), eq(HEAD))).thenReturn(new CommitDetail(
                HEAD, List.of(PARENT), "msg", "a", "2026-10-20", 0, 0, 0, true, List.of()));

        ApiException error = assertThrows(ApiException.class,
                () -> service.diff(PROJECT_ID, HEAD, "src/Other.java"));

        assertEquals(ErrorCode.SNAPSHOT_INCOMPLETE, error.errorCode());
    }

    @Test
    void hostHttpStatusesAreMappedToTheirOwnErrors() {
        when(client.rawFile(any(), any(), any(), eq(HEAD), eq("A.java")))
                .thenThrow(new HostHttpException(404, "not found", null, null));
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(ApiException.class,
                () -> service.readFile(PROJECT_ID, HEAD, "A.java")).errorCode());

        when(client.rawFile(any(), any(), any(), eq(HEAD), eq("B.java")))
                .thenThrow(new HostHttpException(401, "unauthorized", null, null));
        assertEquals(ErrorCode.REPO_UNAUTHORIZED, assertThrows(ApiException.class,
                () -> service.readFile(PROJECT_ID, HEAD, "B.java")).errorCode());

        when(client.rawFile(any(), any(), any(), eq(HEAD), eq("C.java")))
                .thenThrow(new HostHttpException(429, "slow down", "30", null));
        ApiException limited = assertThrows(ApiException.class,
                () -> service.readFile(PROJECT_ID, HEAD, "C.java"));
        assertEquals(ErrorCode.RATE_LIMITED, limited.errorCode());
        assertTrue(limited.errorCode().retryable());

        when(client.rawFile(any(), any(), any(), eq(HEAD), eq("D.java")))
                .thenThrow(new HostHttpException(HostHttpException.TIMEOUT, "timeout", null, null));
        assertEquals(ErrorCode.UPSTREAM_TIMEOUT, assertThrows(ApiException.class,
                () -> service.readFile(PROJECT_ID, HEAD, "D.java")).errorCode());

        when(client.rawFile(any(), any(), any(), eq(HEAD), eq("E.java")))
                .thenThrow(new HostHttpException(503, "unavailable", null, null));
        assertEquals(ErrorCode.REPO_UNAVAILABLE, assertThrows(ApiException.class,
                () -> service.readFile(PROJECT_ID, HEAD, "E.java")).errorCode());
    }

    @Test
    void businessFileNotViewableIsClassifiedAsFileNotReadable() {
        when(client.rawFile(any(), any(), any(), eq(HEAD), eq("F.java")))
                .thenThrow(new BusinessException(ResultCode.GIT_FILE_NOT_VIEWABLE.getCode(), "不可查看"));

        ApiException error = assertThrows(ApiException.class,
                () -> service.readFile(PROJECT_ID, HEAD, "F.java"));

        assertEquals(ErrorCode.FILE_NOT_READABLE, error.errorCode());
    }
}
