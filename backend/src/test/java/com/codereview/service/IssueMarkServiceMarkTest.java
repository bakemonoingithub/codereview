package com.codereview.service;

import com.codereview.common.BusinessException;
import com.codereview.entity.IssueMark;
import com.codereview.entity.ReviewRecord;
import com.codereview.mapper.IssueMarkMapper;
import com.codereview.mapper.ReviewRecordMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 打标 / 撤销的**写入语义**（T-15）。
 *
 * <p>这条路径原先有两个坑，本测试就是它们的回归锁：
 * <ol>
 *   <li>撤销标记是"写回 {@code mark_value = 0} 而不删行" —— 行仍占着唯一键
 *       {@code uk_record_unit_issue}，导致同一条 issue 之后再也标不上（二次标记撞 1062）；</li>
 *   <li>{@code markValue = 0} 在"本来没有标记"时会走 insert 分支，插进一行 {@code mark_value = 0}
 *       的占位垃圾行。</li>
 * </ol>
 * 现在的口径：撤销 = **真删除**（{@code deletePhysically}）且**幂等**；{@code markValue = 0} 不再插入任何行。
 */
class IssueMarkServiceMarkTest {

    private static final Long RECORD_ID = 1001L;
    private static final String UNIT_PATH = "src/main/java/A.java";
    private static final int ISSUE_INDEX = 0;

    private IssueMarkMapper issueMarkMapper;
    private ReviewRecordMapper reviewRecordMapper;
    private IssueMarkService service;

    @BeforeEach
    void setUp() {
        issueMarkMapper = mock(IssueMarkMapper.class);
        reviewRecordMapper = mock(ReviewRecordMapper.class);
        service = new IssueMarkService(issueMarkMapper, reviewRecordMapper);

        ReviewRecord record = new ReviewRecord();
        record.setId(RECORD_ID);
        when(reviewRecordMapper.selectById(RECORD_ID)).thenReturn(record);
    }

    @Test
    void markInsertsWhenNoRowExists() {
        when(issueMarkMapper.selectOne(any())).thenReturn(null);

        IssueMark created = service.mark(RECORD_ID, UNIT_PATH, ISSUE_INDEX, IssueMarkService.MARK_ACCEPTED);

        assertEquals(IssueMarkService.MARK_ACCEPTED, created.getMarkValue());
        assertEquals(RECORD_ID, created.getRecordId());
        verify(issueMarkMapper).insert(any(IssueMark.class));
        verify(issueMarkMapper, never()).updateById(any(IssueMark.class));
    }

    @Test
    void markUpdatesExistingRowInsteadOfInserting() {
        IssueMark existing = row(7L, IssueMarkService.MARK_ACCEPTED);
        when(issueMarkMapper.selectOne(any())).thenReturn(existing);

        IssueMark returned = service.mark(RECORD_ID, UNIT_PATH, ISSUE_INDEX, IssueMarkService.MARK_FALSE_POSITIVE);

        assertSame(existing, returned, "改标应复用同一行");
        assertEquals(IssueMarkService.MARK_FALSE_POSITIVE, existing.getMarkValue());
        verify(issueMarkMapper).updateById(existing);
        verify(issueMarkMapper, never()).insert(any(IssueMark.class));
    }

    @Test
    void markWithNoneDeletesRowPhysically() {
        when(issueMarkMapper.selectOne(any())).thenReturn(row(7L, IssueMarkService.MARK_ACCEPTED));

        IssueMark returned = service.mark(RECORD_ID, UNIT_PATH, ISSUE_INDEX, IssueMarkService.MARK_NONE);

        assertNull(returned, "撤销之后处于\"未标记\"状态，没有可返回的行");
        verify(issueMarkMapper).deletePhysically(7L);
        // 关键回归：撤销**不能**再走"写回 0"的 update
        verify(issueMarkMapper, never()).updateById(any(IssueMark.class));
        verify(issueMarkMapper, never()).insert(any(IssueMark.class));
    }

    @Test
    void markWithNoneOnUnmarkedIssueWritesNothing() {
        when(issueMarkMapper.selectOne(any())).thenReturn(null);

        IssueMark returned = service.mark(RECORD_ID, UNIT_PATH, ISSUE_INDEX, IssueMarkService.MARK_NONE);

        assertNull(returned);
        verify(issueMarkMapper, never()).insert(any(IssueMark.class));
        verify(issueMarkMapper, never()).updateById(any(IssueMark.class));
        verify(issueMarkMapper, never()).deletePhysically(anyLong());
    }

    @Test
    void unmarkDeletesRowPhysically() {
        when(issueMarkMapper.selectOne(any())).thenReturn(row(7L, IssueMarkService.MARK_FALSE_POSITIVE));

        service.unmark(RECORD_ID, UNIT_PATH, ISSUE_INDEX);

        verify(issueMarkMapper).deletePhysically(7L);
        verify(issueMarkMapper, never()).updateById(any(IssueMark.class));
    }

    @Test
    void unmarkIsIdempotentWhenNoRowExists() {
        when(issueMarkMapper.selectOne(any())).thenReturn(null);

        service.unmark(RECORD_ID, UNIT_PATH, ISSUE_INDEX);

        verify(issueMarkMapper, never()).deletePhysically(anyLong());
        verify(issueMarkMapper, never()).insert(any(IssueMark.class));
    }

    @Test
    void markRejectsOutOfRangeValueWithoutTouchingStorage() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.mark(RECORD_ID, UNIT_PATH, ISSUE_INDEX, 3));

        assertEquals("标记取值必须是 0(未标记)/1(误报)/2(已采纳)", e.getMessage());
        verify(issueMarkMapper, never()).insert(any(IssueMark.class));
        verify(issueMarkMapper, never()).deletePhysically(anyLong());
    }

    @Test
    void markRejectsMissingLocator() {
        assertThrows(BusinessException.class, () -> service.mark(RECORD_ID, "  ", ISSUE_INDEX, 1));
        assertThrows(BusinessException.class, () -> service.mark(RECORD_ID, UNIT_PATH, null, 1));
        verify(issueMarkMapper, never()).insert(any(IssueMark.class));
    }

    @Test
    void markRejectsUnknownRecord() {
        when(reviewRecordMapper.selectById(RECORD_ID)).thenReturn(null);

        assertThrows(BusinessException.class,
                () -> service.mark(RECORD_ID, UNIT_PATH, ISSUE_INDEX, IssueMarkService.MARK_ACCEPTED));

        verify(issueMarkMapper, never()).insert(any(IssueMark.class));
    }

    private static IssueMark row(Long id, int markValue) {
        IssueMark m = new IssueMark();
        m.setId(id);
        m.setRecordId(RECORD_ID);
        m.setUnitPath(UNIT_PATH);
        m.setIssueIndex(ISSUE_INDEX);
        m.setMarkValue(markValue);
        return m;
    }
}
