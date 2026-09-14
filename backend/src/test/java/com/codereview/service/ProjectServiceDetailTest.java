package com.codereview.service;

import com.codereview.common.BusinessException;
import com.codereview.config.ReviewProperties;
import com.codereview.entity.Project;
import com.codereview.git.GitHostClient;
import com.codereview.mapper.IssueMarkMapper;
import com.codereview.mapper.ProjectMapper;
import com.codereview.mapper.ReportMapper;
import com.codereview.mapper.ReportRecordMapper;
import com.codereview.mapper.ReviewRecordMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 项目详情（C2 配套）。
 *
 * 详情页要显示"当前在哪个项目"，而原先后端没有 `GET /projects/{id}`，
 * 前端只能拉分页列表去找 —— 翻不到的项目连名字都显示不出来。
 */
class ProjectServiceDetailTest {

    private ProjectMapper projectMapper;
    private ProjectService service;

    @BeforeEach
    void setUp() {
        projectMapper = mock(ProjectMapper.class);
        service = new ProjectService(projectMapper, mock(GitHostClient.class), new ReviewProperties(),
                mock(ReviewRecordMapper.class), mock(ReportMapper.class),
                mock(ReportRecordMapper.class), mock(IssueMarkMapper.class));
    }

    @Test
    void returnsProjectById() {
        Project project = new Project();
        project.setId(9L);
        project.setName("演示项目");
        project.setGiteaUrl("http://gitea.local/team/repo");
        when(projectMapper.selectById(9L)).thenReturn(project);

        Project found = service.detail(9L);

        assertEquals("演示项目", found.getName());
        assertEquals("http://gitea.local/team/repo", found.getGiteaUrl());
    }

    @Test
    void throwsWhenMissing() {
        when(projectMapper.selectById(404L)).thenReturn(null);

        BusinessException e = assertThrows(BusinessException.class, () -> service.detail(404L));

        // 5001 = PROJECT_NOT_FOUND，与前端"项目不存在（可能已被删除）"的提示对应
        assertEquals(5001, e.getCode());
    }
}
