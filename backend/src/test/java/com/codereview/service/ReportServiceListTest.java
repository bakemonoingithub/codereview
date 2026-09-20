package com.codereview.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.codereview.common.PageLimits;
import com.codereview.entity.Report;
import com.codereview.mapper.ReportMapper;
import com.codereview.mapper.ReportRecordMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 报告列表的 pageSize 上限（backlog ①：`/projects/{id}/reports` 原先不设上限）。
 *
 * <p>这个接口是"报告"页在用的，报告正文很大 —— 不截断的代价比别的列表更高。
 */
class ReportServiceListTest {

    private ReportMapper reportMapper;
    private ReportService service;

    @BeforeEach
    void setUp() {
        reportMapper = mock(ReportMapper.class);
        service = new ReportService(reportMapper, mock(ReportRecordMapper.class),
                mock(ModelConfigService.class), mock(ReportExecutor.class));
    }

    @Test
    void listClampsOversizedPageSizeToTheDocumentedCap() {
        when(reportMapper.selectPage(any(Page.class), any())).thenAnswer(inv -> inv.getArgument(0));

        service.list(9L, 1, 100_000);

        ArgumentCaptor<Page<Report>> captor = ArgumentCaptor.forClass(Page.class);
        verify(reportMapper).selectPage(captor.capture(), any());
        assertEquals(PageLimits.MAX_PAGE_SIZE, captor.getValue().getSize());
        assertEquals(1, captor.getValue().getCurrent());
    }

    @Test
    void listFallsBackToOneForNonPositivePageSize() {
        when(reportMapper.selectPage(any(Page.class), any())).thenAnswer(inv -> inv.getArgument(0));

        service.list(9L, 1, 0);

        ArgumentCaptor<Page<Report>> captor = ArgumentCaptor.forClass(Page.class);
        verify(reportMapper).selectPage(captor.capture(), any());
        assertEquals(1, captor.getValue().getSize());
    }
}
