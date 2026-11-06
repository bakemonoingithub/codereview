package com.codereview.agent.repository;

import com.codereview.agent.api.ApiException;
import com.codereview.agent.api.ErrorCode;
import com.codereview.agent.db.AgentDatabase;
import com.codereview.agent.entity.AgentProject;
import com.codereview.agent.mapper.AgentProjectMapper;
import com.codereview.git.FixedComparisonService;
import com.codereview.git.GitHostClient;
import com.codereview.git.GitMirror;
import com.codereview.git.PullRequests;
import com.codereview.git.TestGitHostClients;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 审查修复：固定比较复用，以及版本移动优先于比较失败。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PullRequestReviewFixTest {

    private static final String PROJECT_ID = "01J9Z6Q3W4X5Y6Z7A8B9C0D1E2";
    private static final String OTHER = "dddddddddddddddddddddddddddddddddddddddd";

    @Mock
    private AgentDatabase db;
    @Mock
    private AgentProjectMapper projectMapper;
    @Mock
    private GitHostClient client;

    private Path tempDir;
    private RepositoryReadService service;
    private FixedComparisonService comparisons;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Path.of("target", "pr-review-fix", UUID.randomUUID().toString());
        Files.createDirectories(tempDir);
        comparisons = spy(new FixedComparisonService(new GitMirror(tempDir.resolve("mirrors").toFile(), 30)));
        service = new RepositoryReadService(db, TestGitHostClients.routingTo(client),
                new RepositoryProperties(), comparisons);
        AgentProject project = new AgentProject();
        project.setId(PROJECT_ID);
        project.setRepoUrl("https://github.com/acme/order-service");
        project.setCredential("token");
        project.setCredentialType(1);
        project.setRevision(1);
        when(db.mapper(AgentProjectMapper.class)).thenReturn(projectMapper);
        when(projectMapper.selectById(any())).thenReturn(project);
        when(client.supportsLocalComparison()).thenReturn(true);
        when(client.pullRequestProvider()).thenReturn("github");
    }

    @Test
    void diffRangeReusesOneFrozenComparisonForEveryTarget() throws Exception {
        String base;
        String source;
        Path work = tempDir.resolve("work");
        try (Git git = Git.init().setDirectory(work.toFile()).call()) {
            base = commit(git, work, Map.of("a.txt", "1\n", "b.txt", "1\n"), "base");
            source = commit(git, work, Map.of("a.txt", "2\n", "b.txt", "3\n"), "source");
        }
        when(client.mirrorCloneUrl(any())).thenReturn(work.toUri().toString());
        when(client.rawFile(any(), any(), any(), any(), any())).thenReturn("class A {}\n");

        service.diffRange(PROJECT_ID, base, source, "a.txt");
        service.diffRange(PROJECT_ID, base, source, "b.txt");

        verify(comparisons, times(1)).trees(any(), any(), any(), eq(base), eq(source), anyInt());
    }

    @Test
    void movedPullWithoutMergeBaseIsSourceMoved() throws Exception {
        CommitPair pair = unrelatedCommits();
        when(client.mirrorCloneUrl(any())).thenReturn(pair.work.toUri().toString());
        when(client.pullRequest(any(), any(), any(), eq(12L))).thenReturn(pull(pair.target, pair.source, true));

        ApiException failure = assertThrows(ApiException.class,
                () -> service.bindPullRequest(PROJECT_ID, 12, OTHER, OTHER, OTHER));

        assertEquals(ErrorCode.SOURCE_MOVED, failure.errorCode());
    }

    @Test
    void unchangedPullWithoutMergeBaseStaysNoMergeBase() throws Exception {
        CommitPair pair = unrelatedCommits();
        when(client.mirrorCloneUrl(any())).thenReturn(pair.work.toUri().toString());
        when(client.pullRequest(any(), any(), any(), eq(12L))).thenReturn(pull(pair.target, pair.source, true));

        ApiException failure = assertThrows(ApiException.class,
                () -> service.bindPullRequest(PROJECT_ID, 12, pair.source, pair.target, OTHER));

        assertEquals(ErrorCode.VALIDATION_FAILED, failure.errorCode());
        assertEquals(PullRequestViews.NO_MERGE_BASE, ((Map<?, ?>) failure.details()).get("reason"));
    }

    @Test
    void closedPullIsRejectedBeforeVersionMovement() throws Exception {
        CommitPair pair = unrelatedCommits();
        when(client.mirrorCloneUrl(any())).thenReturn(pair.work.toUri().toString());
        when(client.pullRequest(any(), any(), any(), eq(12L))).thenReturn(pull(pair.target, pair.source, false));

        ApiException failure = assertThrows(ApiException.class,
                () -> service.bindPullRequest(PROJECT_ID, 12, OTHER, OTHER, OTHER));

        assertEquals(ErrorCode.VALIDATION_FAILED, failure.errorCode());
        assertEquals(PullRequestViews.PR_NOT_OPEN, ((Map<?, ?>) failure.details()).get("reason"));
    }

    private PullRequests.Item pull(String target, String source, boolean open) {
        return new PullRequests.Item(12, "标题", open ? "open" : "closed", false, false, "chen",
                "feature", "dev", source, target, "acme/order-service", "acme/order-service",
                "https://example/12", "2026-11-06");
    }

    private CommitPair unrelatedCommits() throws Exception {
        Path work = tempDir.resolve("unrelated");
        String target;
        String source;
        try (Git git = Git.init().setDirectory(work.toFile()).call()) {
            target = commit(git, work, Map.of("a.txt", "1\n"), "main");
            git.checkout().setOrphan(true).setName("orphan").call();
            Files.writeString(work.resolve("b.txt"), "2\n", StandardCharsets.UTF_8);
            git.add().addFilepattern("b.txt").call();
            source = git.commit().setMessage("orphan").setSign(false)
                    .setAuthor(ident()).setCommitter(ident()).call().name();
        }
        return new CommitPair(work, target, source);
    }

    private static PersonIdent ident() {
        return new PersonIdent("tester", "tester@example.com");
    }

    private static String commit(Git git, Path work, Map<String, String> files, String message) throws Exception {
        for (Map.Entry<String, String> file : files.entrySet()) {
            Path path = work.resolve(file.getKey());
            Files.createDirectories(path.getParent());
            Files.writeString(path, file.getValue(), StandardCharsets.UTF_8);
            git.add().addFilepattern(file.getKey()).call();
        }
        return git.commit().setMessage(message).setSign(false).setAuthor(ident()).setCommitter(ident()).call().name();
    }

    private record CommitPair(Path work, String target, String source) {
    }
}
