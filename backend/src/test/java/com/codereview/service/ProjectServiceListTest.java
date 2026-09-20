package com.codereview.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.codereview.common.PageLimits;
import com.codereview.config.ReviewProperties;
import com.codereview.entity.Project;
import com.codereview.git.TestGitHostClients;
import com.codereview.mapper.IssueMarkMapper;
import com.codereview.mapper.ProjectMapper;
import com.codereview.mapper.ReportMapper;
import com.codereview.mapper.ReportRecordMapper;
import com.codereview.mapper.ReviewRecordMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 项目列表的 pageSize 上限（backlog ①：`/projects` 原先不设上限，调用方给 100000 就真去查十万行）。
 *
 * <p>断言方式与 {@code ReviewServiceListTest} 一致：mock mapper，捕获**实际下传的 {@link Page}**。
 * 把 Page 交给内存里的 mock，链接器自然不参与，所以这里测的是 service 自己的显式截断；
 * 插件的 `maxLimit` 兜底另有 {@code MybatisPlusConfigTest} 断言。
 */
class ProjectServiceListTest {

    private ProjectMapper projectMapper;
    private ProjectService service;

    @BeforeEach
    void setUp() {
        projectMapper = mock(ProjectMapper.class);
        service = new ProjectService(projectMapper, TestGitHostClients.withMockClient(), new ReviewProperties(),
                mock(ReviewRecordMapper.class), mock(ReportMapper.class),
                mock(ReportRecordMapper.class), mock(IssueMarkMapper.class));
    }

    @Test
    void listClampsOversizedPageSizeToTheDocumentedCap() {
        when(projectMapper.selectPage(any(Page.class), any())).thenAnswer(inv -> inv.getArgument(0));

        service.list(1, 100_000);

        ArgumentCaptor<Page<Project>> captor = ArgumentCaptor.forClass(Page.class);
        verify(projectMapper).selectPage(captor.capture(), any());
        assertEquals(PageLimits.MAX_PAGE_SIZE, captor.getValue().getSize());
    }

    @Test
    void listFallsBackToOneForNonPositiveParams() {
        when(projectMapper.selectPage(any(Page.class), any())).thenAnswer(inv -> inv.getArgument(0));

        service.list(0, -1);

        ArgumentCaptor<Page<Project>> captor = ArgumentCaptor.forClass(Page.class);
        verify(projectMapper).selectPage(captor.capture(), any());
        assertEquals(1, captor.getValue().getSize());
        assertEquals(1, captor.getValue().getCurrent());
    }
}
