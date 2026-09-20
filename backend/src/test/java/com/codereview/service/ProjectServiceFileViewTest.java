package com.codereview.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.codereview.common.BusinessException;
import com.codereview.config.ReviewProperties;
import com.codereview.dto.FileContentResp;
import com.codereview.entity.Project;
import com.codereview.git.CommitDetail;
import com.codereview.git.GitHostClient;
import com.codereview.git.GitHostClientRegistry;
import com.codereview.git.TestGitHostClients;
import com.codereview.mapper.IssueMarkMapper;
import com.codereview.mapper.ProjectMapper;
import com.codereview.mapper.ReportMapper;
import com.codereview.mapper.ReportRecordMapper;
import com.codereview.mapper.ReviewRecordMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 文件查看（{@code GET /api/projects/{id}/file}）。
 *
 * <p>三条契约：① 只有"可查看的文本文件"能看（判定复用后端唯一判定点 `ReviewableFiles`）；
 * ② 路径不能穿越到仓库外、也不能写成绝对路径 —— 它会被直接拼进 Git 宿主的 URL；
 * ③ 默认截断到 1000 行并如实报告总行数，`full=true` 才放宽，且始终受字节上限约束。
 */
class ProjectServiceFileViewTest {

    private static final String SHA = "abcdef1234567890";

    private ProjectMapper projectMapper;
    private GitHostClient git;
    private ProjectService service;
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void setUp() {
        projectMapper = mock(ProjectMapper.class);
        git = mock(GitHostClient.class);
        service = new ProjectService(projectMapper, TestGitHostClients.routingTo(git), new ReviewProperties(),
                mock(ReviewRecordMapper.class), mock(ReportMapper.class),
                mock(ReportRecordMapper.class), mock(IssueMarkMapper.class));

        Logger logger = (Logger) LoggerFactory.getLogger(ProjectService.class);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);

        Project p = new Project();
        p.setId(9L);
        p.setGiteaUrl("https://github.com/team/repo");
        p.setCredential("tok");
        when(projectMapper.selectById(9L)).thenReturn(p);
    }

    /** 截断原本只在响应里回报，日志里查不到 —— 这里锁住"被截断要留痕"（backlog ⑥）。 */
    @Test
    void truncationIsLoggedForObservability() {
        when(git.rawFile(any(), any(), any(), any(), any())).thenReturn(lines(2500));

        service.fileView(9L, "main", "src/A.java", "content", false);

        assertTrue(logs.list.stream().anyMatch(e -> e.getLevel() == Level.WARN
                        && e.getFormattedMessage().contains("文件内容被截断")
                        && e.getFormattedMessage().contains("src/A.java")),
                "被截断时必须留一条带路径的 WARN：" + logs.list);
    }

    @Test
    void noWarningWhenNothingWasTruncated() {
        when(git.rawFile(any(), any(), any(), any(), any())).thenReturn("class A {}\n");

        service.fileView(9L, "main", "src/A.java", "content", false);

        assertTrue(logs.list.stream().noneMatch(e -> e.getLevel() == Level.WARN),
                "没截断就不该刷 WARN：" + logs.list);
    }

    private static String lines(int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= count; i++) {
            sb.append("line-").append(i).append('\n');
        }
        return sb.toString();
    }

    @Test
    void readsTextFileAndReportsTotalLines() {
        when(git.rawFile(any(), any(), any(), eq("main"), eq("src/A.java")))
                .thenReturn("class A {}\n");

        FileContentResp resp = service.fileView(9L, "main", "src/A.java", "content", false);

        assertEquals("content", resp.mode());
        assertEquals("src/A.java", resp.path());
        assertEquals("main", resp.ref());
        assertEquals("class A {}\n", resp.content());
        assertFalse(resp.truncated());
        assertEquals(1, resp.totalLines());
    }

    @Test
    void truncatesAtThousandLinesByDefaultAndKeepsRealTotal() {
        when(git.rawFile(any(), any(), any(), any(), any())).thenReturn(lines(2500));

        FileContentResp resp = service.fileView(9L, "main", "src/A.java", "content", false);

        assertTrue(resp.truncated());
        assertEquals(1000, resp.content().split("\n", -1).length);
        assertEquals(2500, resp.totalLines());
    }

    @Test
    void fullRelaxesLineLimitButStillRespectsByteCap() {
        when(git.rawFile(any(), any(), any(), any(), any())).thenReturn(lines(30_000));

        FileContentResp resp = service.fileView(9L, "main", "src/A.java", "content", true);

        // 20000 行是硬上限：30k 行的文件即使点了「加载全文」也仍会被截断并如实标记
        assertTrue(resp.truncated());
        assertEquals(20_000, resp.content().split("\n", -1).length);
        assertEquals(30_000, resp.totalLines());
    }

    @Test
    void byteCapCutsASingleHugeLine() {
        when(git.rawFile(any(), any(), any(), any(), any())).thenReturn("x".repeat(5 * 1024 * 1024));

        FileContentResp resp = service.fileView(9L, "main", "src/A.java", "content", false);

        assertTrue(resp.truncated());
        assertTrue(resp.content().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= ProjectService.VIEW_MAX_BYTES);
    }

    @Test
    void diffModeReturnsTheCommitPatchForThatFile() {
        CommitDetail detail = new CommitDetail(SHA, List.of("p1"), "msg", "author", "2026-09-01",
                1, 1, 2, false,
                List.of(new com.codereview.git.ChangedFile("src/A.java", null, "modified", 1, 1, 1,
                        "@@ -1 +1 @@\n-a\n+b")));
        when(git.commitDetail(any(), any(), any(), eq(SHA))).thenReturn(detail);

        FileContentResp resp = service.fileView(9L, SHA, "src/A.java", "diff", false);

        assertEquals("diff", resp.mode());
        assertTrue(resp.content().contains("@@ -1 +1 @@"));
        assertEquals(3, resp.totalLines());
    }

    @Test
    void diffModeWithoutPatchReturnsEmptyContentInsteadOfFailing() {
        CommitDetail detail = new CommitDetail(SHA, List.of("p1"), "msg", "author", "2026-09-01",
                0, 0, 0, false,
                List.of(new com.codereview.git.ChangedFile("src/A.java", null, "modified", null, null, null, null)));
        when(git.commitDetail(any(), any(), any(), eq(SHA))).thenReturn(detail);

        // 该文件在这份提交里没有 patch（宿主对过大差异会省略）⇒ 界面提示"无可显示差异"，而不是报错
        FileContentResp resp = service.fileView(9L, SHA, "src/Other.java", "diff", false);

        assertEquals("", resp.content());
        assertFalse(resp.truncated());
        assertEquals(0, resp.totalLines());
    }

    @Test
    void diffModeRequiresAShaNotABranchName() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.fileView(9L, "main", "src/A.java", "diff", false));

        assertEquals(1001, e.getCode());
        assertTrue(e.getMessage().contains("sha"), "实际：" + e.getMessage());
        verify(git, never()).commitDetail(any(), any(), any(), any());
    }

    @Test
    void nonTextFileIsRejectedWithItsOwnCode() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.fileView(9L, "main", "logo.png", "content", false));

        assertEquals(5004, e.getCode());
        assertTrue(e.getMessage().contains("logo.png"));
        verify(git, never()).rawFile(any(), any(), any(), any(), any());
    }

    @Test
    void extensionlessTextFileIsAlsoRejectedBecauseItIsNotInTheWhitelist() {
        // 口径与"可审查"一致：Makefile/Dockerfile/LICENSE 不在白名单内（这是刻意的，见 ReviewableFiles）
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.fileView(9L, "main", "Makefile", "content", false));

        assertEquals(5004, e.getCode());
    }

    @Test
    void pathTraversalAndAbsolutePathsAreRejected() {
        for (String bad : List.of("../../etc/passwd", "/etc/passwd", "src/../../secret.java", "src\\A.java", ".")) {
            BusinessException e = assertThrows(BusinessException.class,
                    () -> service.fileView(9L, "main", bad, "content", false), "应拒绝：" + bad);
            assertEquals(1001, e.getCode(), "应拒绝：" + bad);
        }
        verify(git, never()).rawFile(any(), any(), any(), any(), any());
    }

    @Test
    void unknownModeAndBlankRefAreParamErrors() {
        assertEquals(1001, assertThrows(BusinessException.class,
                () -> service.fileView(9L, "main", "src/A.java", "raw", false)).getCode());
        assertEquals(1001, assertThrows(BusinessException.class,
                () -> service.fileView(9L, " ", "src/A.java", "content", false)).getCode());
    }

    @Test
    void refIsPassedThroughUntouchedSoShaPinnedContentStaysPinned() {
        when(git.rawFile(any(), any(), any(), eq(SHA), any())).thenReturn("x\n");

        FileContentResp resp = service.fileView(9L, SHA, "src/A.java", "content", false);

        assertEquals(SHA, resp.ref());
        verify(git).rawFile(any(), any(), any(), eq(SHA), eq("src/A.java"));
    }

    /**
     * 路由仍按 host 走：非 GitHub 的项目在这里就会显式报"未接入"。
     *
     * <p>注意这里**不能**用 {@link TestGitHostClients#routingTo} —— 它把 supports 打成恒真，
     * 什么 host 都会命中；要验证"没有实现服务这个 host"，得让 mock 保持默认（supports=false）。
     */
    @Test
    void unsupportedHostStillFailsLoudly() {
        Project internal = new Project();
        internal.setId(8L);
        internal.setGiteaUrl("http://192.104.224.172/gitea/team/repo");
        when(projectMapper.selectById(8L)).thenReturn(internal);
        // 必须是**未被 TestGitHostClients 打桩过**的新 mock：setUp 里那个的 supports 已被设为恒真
        GitHostClient unknownHost = mock(GitHostClient.class);
        ProjectService strictService = new ProjectService(projectMapper,
                new GitHostClientRegistry(List.of(unknownHost)), new ReviewProperties(),
                mock(ReviewRecordMapper.class), mock(ReportMapper.class),
                mock(ReportRecordMapper.class), mock(IssueMarkMapper.class));

        BusinessException e = assertThrows(BusinessException.class,
                () -> strictService.fileView(8L, "main", "src/A.java", "content", false));

        assertEquals(5003, e.getCode());
        assertTrue(e.getMessage().contains("192.104.224.172"), "报错要点名 host：" + e.getMessage());
        verify(unknownHost, never()).rawFile(any(), any(), any(), any(), any());
    }
}
