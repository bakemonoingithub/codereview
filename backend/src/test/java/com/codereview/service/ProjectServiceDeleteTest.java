package com.codereview.service;

import com.codereview.common.BusinessException;
import com.codereview.config.ReviewProperties;
import com.codereview.dto.DeleteImpactResp;
import com.codereview.entity.Project;
import com.codereview.entity.Report;
import com.codereview.entity.ReviewRecord;
import com.codereview.git.GitHostClient;
import com.codereview.mapper.IssueMarkMapper;
import com.codereview.mapper.ProjectMapper;
import com.codereview.mapper.ReportMapper;
import com.codereview.mapper.ReportRecordMapper;
import com.codereview.mapper.ReviewRecordMapper;
import com.codereview.review.ReviewStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 项目删除：**拦截规则**与**级联范围**。
 *
 * <p>删除本身只是一行 {@code deleteById}，真正易错的是"什么时候不许删"和
 * "删完之后有没有留下孤儿数据"，所以测试集中在这两处。
 */
class ProjectServiceDeleteTest {

    private static final int WINDOW_MINUTES = 60;

    private ProjectMapper projectMapper;
    private ReviewRecordMapper reviewRecordMapper;
    private ReportMapper reportMapper;
    private ReportRecordMapper reportRecordMapper;
    private IssueMarkMapper issueMarkMapper;
    private ReviewProperties props;
    private ProjectService service;

    @BeforeEach
    void setUp() {
        projectMapper = mock(ProjectMapper.class);
        reviewRecordMapper = mock(ReviewRecordMapper.class);
        reportMapper = mock(ReportMapper.class);
        reportRecordMapper = mock(ReportRecordMapper.class);
        issueMarkMapper = mock(IssueMarkMapper.class);
        props = new ReviewProperties();
        service = new ProjectService(projectMapper, mock(GitHostClient.class), props,
                reviewRecordMapper, reportMapper, reportRecordMapper, issueMarkMapper);
    }

    private static Project project() {
        Project p = new Project();
        p.setId(1L);
        p.setName("演示项目");
        p.setGiteaUrl("http://gitea.local/team/repo");
        return p;
    }

    private static ReviewRecord record(long id, int status, LocalDateTime createdAt) {
        ReviewRecord r = new ReviewRecord();
        r.setId(id);
        r.setStatus(status);
        r.setCreatedAt(createdAt);
        return r;
    }

    // ------------------------------------------------------------------
    // 拦截规则本体（纯函数直测）
    // ------------------------------------------------------------------

    @Test
    void blocksQueuedReviewInsideWindow() {
        LocalDateTime now = LocalDateTime.now();
        assertTrue(ProjectService.isWithinBlockWindow(
                record(1L, ReviewStatus.QUEUED, now.minusMinutes(1)), now, WINDOW_MINUTES));
    }

    @Test
    void blocksRunningReviewInsideWindow() {
        LocalDateTime now = LocalDateTime.now();
        assertTrue(ProjectService.isWithinBlockWindow(
                record(1L, ReviewStatus.RUNNING, now.minusMinutes(30)), now, WINDOW_MINUTES));
    }

    @Test
    void doesNotBlockStaleReview() {
        // 超过窗口 = 视为已卡死，必须放行，否则一个卡死的任务会让项目永远删不掉
        LocalDateTime now = LocalDateTime.now();
        assertFalse(ProjectService.isWithinBlockWindow(
                record(1L, ReviewStatus.RUNNING, now.minusMinutes(90)), now, WINDOW_MINUTES));
    }

    @Test
    void doesNotBlockFinishedReview() {
        LocalDateTime now = LocalDateTime.now();
        assertFalse(ProjectService.isWithinBlockWindow(
                record(1L, ReviewStatus.SUCCESS, now.minusMinutes(1)), now, WINDOW_MINUTES));
        assertFalse(ProjectService.isWithinBlockWindow(
                record(1L, ReviewStatus.FAILED, now.minusMinutes(1)), now, WINDOW_MINUTES));
        assertFalse(ProjectService.isWithinBlockWindow(
                record(1L, ReviewStatus.PARTIAL, now.minusMinutes(1)), now, WINDOW_MINUTES));
    }

    @Test
    void doesNotBlockWhenCreatedAtMissing() {
        // 历史脏数据：宁可允许删除，也不让一条脏数据把项目永久锁死
        LocalDateTime now = LocalDateTime.now();
        assertFalse(ProjectService.isWithinBlockWindow(
                record(1L, ReviewStatus.RUNNING, null), now, WINDOW_MINUTES));
        assertFalse(ProjectService.isWithinBlockWindow(null, now, WINDOW_MINUTES));
    }

    @Test
    void windowBoundaryIsExclusive() {
        // 恰好等于窗口边界时不再阻塞（"不足 N 分钟"是开区间）
        LocalDateTime now = LocalDateTime.now();
        assertFalse(ProjectService.isWithinBlockWindow(
                record(1L, ReviewStatus.RUNNING, now.minusMinutes(WINDOW_MINUTES)), now, WINDOW_MINUTES));
        assertTrue(ProjectService.isWithinBlockWindow(
                record(1L, ReviewStatus.RUNNING, now.minusMinutes(WINDOW_MINUTES).plusSeconds(1)), now, WINDOW_MINUTES));
    }

    @Test
    void windowFollowsConfiguredMinutes() {
        LocalDateTime now = LocalDateTime.now();
        ReviewRecord r = record(1L, ReviewStatus.RUNNING, now.minusMinutes(30));

        assertTrue(ProjectService.isWithinBlockWindow(r, now, 60), "60 分钟窗口内，30 分钟前的任务应阻塞");
        assertFalse(ProjectService.isWithinBlockWindow(r, now, 10), "阈值调成 10 分钟后，同一个任务不再阻塞");
    }

    // ------------------------------------------------------------------
    // 服务级：拒绝删除时不能有任何副作用
    // ------------------------------------------------------------------

    @Test
    void refusesWhenReviewIsStillRunning() {
        when(projectMapper.selectById(1L)).thenReturn(project());
        when(reviewRecordMapper.selectList(any()))
                .thenReturn(List.of(record(11L, ReviewStatus.RUNNING, LocalDateTime.now())));

        BusinessException e = assertThrows(BusinessException.class, () -> service.delete(1L));

        assertEquals(1005, e.getCode());
        // 关键：被拒绝时**不能有任何删除动作**。只断言"抛了异常"是不够的 ——
        // 那会放过"先删后校验"这种错误实现。
        verify(issueMarkMapper, never()).delete(any());
        verify(reportRecordMapper, never()).delete(any());
        verify(reviewRecordMapper, never()).delete(any());
        verify(reportMapper, never()).delete(any());
        verify(projectMapper, never()).deleteById(anyLong());
    }

    // ------------------------------------------------------------------
    // 级联范围
    // ------------------------------------------------------------------

    @Test
    void deletesProjectAndCascadesToChildren() {
        when(projectMapper.selectById(1L)).thenReturn(project());
        // 已完成的记录不构成阻塞，但它们的 id 仍要被级联清理
        when(reviewRecordMapper.selectList(any())).thenReturn(List.of(
                record(11L, ReviewStatus.SUCCESS, LocalDateTime.now()),
                record(12L, ReviewStatus.SUCCESS, LocalDateTime.now())));
        Report report = new Report();
        report.setId(21L);
        when(reportMapper.selectList(any())).thenReturn(List.of(report));

        service.delete(1L);

        // issue_mark 是准确率(指标 5)的数据源，不清理会留下指向已删记录的孤儿标记
        verify(issueMarkMapper).delete(any());
        // 报告-记录关联表：按记录删一次、按报告再删一次
        verify(reportRecordMapper, times(2)).delete(any());
        verify(reviewRecordMapper).delete(any());
        verify(reportMapper).delete(any());
        verify(projectMapper).deleteById(1L);
    }

    @Test
    void skipsChildDeletesWhenProjectHasNoRecords() {
        when(projectMapper.selectById(1L)).thenReturn(project());
        // selectList 未 stub：Mockito 对 List 返回空集合
        service.delete(1L);

        // 没有子记录时不应发出 in () 空条件的删除语句
        verify(issueMarkMapper, never()).delete(any());
        verify(reportRecordMapper, never()).delete(any());
        verify(projectMapper).deleteById(1L);
    }

    // ------------------------------------------------------------------
    // 影响范围预览
    // ------------------------------------------------------------------

    @Test
    void impactReportsCountsAndConfiguredThreshold() {
        props.setDeleteBlockMinutes(15);
        when(projectMapper.selectById(1L)).thenReturn(project());
        when(reviewRecordMapper.selectCount(any())).thenReturn(3L);
        when(reportMapper.selectCount(any())).thenReturn(2L);

        DeleteImpactResp impact = service.deleteImpact(1L);

        assertEquals(3, impact.recordCount());
        assertEquals(2, impact.reportCount());
        assertFalse(impact.blocked());
        assertNull(impact.blockReason());
        assertEquals(15, impact.thresholdMinutes(), "阈值来自配置，前端据此解释要等多久");
    }

    @Test
    void impactReportsBlockedWithReason() {
        when(projectMapper.selectById(1L)).thenReturn(project());
        when(reviewRecordMapper.selectList(any()))
                .thenReturn(List.of(record(11L, ReviewStatus.QUEUED, LocalDateTime.now())));

        DeleteImpactResp impact = service.deleteImpact(1L);

        assertTrue(impact.blocked());
        assertTrue(impact.blockReason() != null && impact.blockReason().contains("正在进行"),
                "被阻塞时必须给出可展示的原因");
    }

    @Test
    void deleteRejectsUnknownProject() {
        when(projectMapper.selectById(404L)).thenReturn(null);

        BusinessException e = assertThrows(BusinessException.class, () -> service.delete(404L));

        assertEquals(5001, e.getCode());
    }
}
