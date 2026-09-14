package com.codereview.service;

import com.codereview.common.BusinessException;
import com.codereview.dto.ProjectCreateReq;
import com.codereview.entity.Project;
import com.codereview.git.GitHostClient;
import com.codereview.mapper.ProjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 项目创建时的**仓库地址判重**（V5 移除 {@code uk_gitea_url} 后由应用层承接）。
 *
 * <p>为什么必须判重而不能继续依赖唯一索引：唯一索引 + 逻辑删除会让**已删除的项目
 * 继续占用该地址**，于是"删掉后用同一仓库重建"必然失败。MySQL 不支持"仅对未删除行唯一"
 * 的部分唯一索引，所以只能下移到应用层。
 *
 * <p>注：{@code verify(...).insert(...)} 必须用**带类型**的匹配器。MyBatis-Plus 3.5.7 的
 * {@code BaseMapper} 同时有 {@code insert(T)} 与 {@code insert(Collection<T>)} 两个重载，
 * 裸 {@code any()} 会因歧义而编译失败。
 */
class ProjectServiceCreateTest {

    private ProjectMapper projectMapper;
    private GitHostClient gitHostClient;
    private ProjectService service;

    @BeforeEach
    void setUp() {
        projectMapper = mock(ProjectMapper.class);
        gitHostClient = mock(GitHostClient.class);
        service = new ProjectService(projectMapper, gitHostClient);
    }

    private static ProjectCreateReq req() {
        return new ProjectCreateReq("演示项目", "http://gitea.local/team/repo", null, 1);
    }

    @Test
    void rejectsDuplicateUrlBeforeTouchingRemote() {
        when(projectMapper.selectCount(any())).thenReturn(1L);

        BusinessException e = assertThrows(BusinessException.class, () -> service.create(req()));

        assertEquals(1005, e.getCode());
        verify(projectMapper, never()).insert(any(Project.class));
        // 地址已被占用时不该再打一次远端：判重必须排在连通性验证之前
        verify(gitHostClient, never()).branches(any(), any(), any(), any());
    }

    @Test
    void createsWhenUrlFree() {
        when(projectMapper.selectCount(any())).thenReturn(0L);
        when(gitHostClient.branches(any(), any(), any(), any())).thenReturn(List.of("main"));

        Project created = service.create(req());

        assertEquals("演示项目", created.getName());
        assertEquals("main", created.getCurrentBranch());
        verify(projectMapper).insert(any(Project.class));
    }

    @Test
    void reportsConnectivityFailureWithoutInserting() {
        when(projectMapper.selectCount(any())).thenReturn(0L);
        when(gitHostClient.branches(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("connection refused"));

        BusinessException e = assertThrows(BusinessException.class, () -> service.create(req()));

        assertEquals(5002, e.getCode());
        verify(projectMapper, never()).insert(any(Project.class));
    }
}
