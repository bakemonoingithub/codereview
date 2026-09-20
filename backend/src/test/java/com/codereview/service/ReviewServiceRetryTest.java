package com.codereview.service;

import com.codereview.common.BusinessException;
import com.codereview.entity.ReviewRecord;
import com.codereview.git.TestGitHostClients;
import com.codereview.mapper.ModelConfigMapper;
import com.codereview.mapper.ProjectMapper;
import com.codereview.mapper.PromptMapper;
import com.codereview.mapper.PromptVersionMapper;
import com.codereview.mapper.ReviewRecordMapper;
import com.codereview.mapper.ReviewStrategyMapper;
import com.codereview.review.ReviewStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「重审」允许的状态集合（backlog ⑥ 的前后端不一致）。
 *
 * <p>前端 `canRetry` 只认失败(3)/部分成功(4)，后端原先的判据却是 `status < 2`（未结束才拒）
 * —— 即**成功(2)也能重审**。前端按钮是灰的，所以这条路径只能被直接调 API 触发；
 * 而重审会覆盖已确认的结果资产。这里把口径钉死在后端这一侧。
 */
class ReviewServiceRetryTest {

    private ReviewRecordMapper reviewRecordMapper;
    private ReviewExecutor reviewExecutor;
    private ReviewService service;

    @BeforeEach
    void setUp() {
        reviewRecordMapper = mock(ReviewRecordMapper.class);
        reviewExecutor = mock(ReviewExecutor.class);
        service = new ReviewService(reviewRecordMapper, mock(ProjectMapper.class), mock(ReviewStrategyMapper.class),
                mock(ModelConfigMapper.class), TestGitHostClients.withMockClient(), reviewExecutor,
                mock(PromptMapper.class), mock(PromptVersionMapper.class));
    }

    private ReviewRecord record(int status) {
        ReviewRecord r = new ReviewRecord();
        r.setId(1L);
        r.setStatus(status);
        when(reviewRecordMapper.selectById(1L)).thenReturn(r);
        return r;
    }

    @Test
    void failedRecordCanBeRetried() {
        record(ReviewStatus.FAILED);

        service.retry(1L);

        verify(reviewExecutor).retry(1L);
    }

    @Test
    void partialRecordCanBeRetried() {
        record(ReviewStatus.PARTIAL);

        service.retry(1L);

        verify(reviewExecutor).retry(1L);
    }

    @Test
    void successfulRecordIsRejectedWithAReason() {
        record(ReviewStatus.SUCCESS);

        BusinessException e = assertThrows(BusinessException.class, () -> service.retry(1L));

        assertEquals(1001, e.getCode());
        assertTrue(e.getMessage().contains("成功"), "拒绝原因要说明当前状态：" + e.getMessage());
        verify(reviewExecutor, never()).retry(1L);
    }

    @Test
    void runningAndQueuedRecordsAreRejected() {
        record(ReviewStatus.RUNNING);

        assertThrows(BusinessException.class, () -> service.retry(1L));
        verify(reviewExecutor, never()).retry(1L);
    }

    @Test
    void nullStatusIsRejected() {
        record(ReviewStatus.SUCCESS);
        when(reviewRecordMapper.selectById(1L)).thenReturn(new ReviewRecord());

        assertThrows(BusinessException.class, () -> service.retry(1L));
    }

    @Test
    void retryabilityMatchesTheFrontendRule() {
        assertTrue(ReviewService.isRetryable(ReviewStatus.FAILED));
        assertTrue(ReviewService.isRetryable(ReviewStatus.PARTIAL));
        assertFalse(ReviewService.isRetryable(ReviewStatus.SUCCESS));
        assertFalse(ReviewService.isRetryable(ReviewStatus.RUNNING));
        assertFalse(ReviewService.isRetryable(ReviewStatus.QUEUED));
        assertFalse(ReviewService.isRetryable(null));
    }
}
