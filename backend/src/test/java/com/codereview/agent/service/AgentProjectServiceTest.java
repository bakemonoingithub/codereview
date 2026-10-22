package com.codereview.agent.service;

import com.codereview.agent.api.ApiException;
import com.codereview.agent.api.ErrorCode;
import com.codereview.agent.db.AgentDatabase;
import com.codereview.agent.dto.ProjectDtos;
import com.codereview.agent.entity.AgentProject;
import com.codereview.agent.mapper.AgentProjectMapper;
import com.codereview.agent.mapper.AgentReviewTaskMapper;
import com.codereview.common.BusinessException;
import com.codereview.common.ResultCode;
import com.codereview.git.GitHostClient;
import com.codereview.git.GitHostClientRegistry;
import com.codereview.git.GitRepoRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.Serializable;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentProjectServiceTest {

    private static final String REPO = "https://gitea.example.com/team/order-service";

    @Mock
    private AgentDatabase db;
    @Mock
    private GitHostClientRegistry registry;
    @Mock
    private AgentProjectMapper mapper;
    @Mock
    private AgentReviewTaskMapper taskMapper;
    @Mock
    private GitHostClient client;

    private AgentProjectService service;

    @BeforeEach
    void setUp() {
        service = new AgentProjectService(db, registry);
        when(db.mapper(AgentProjectMapper.class)).thenReturn(mapper);
        when(db.mapper(AgentReviewTaskMapper.class)).thenReturn(taskMapper);
        when(registry.forRepo(any())).thenReturn(client);
    }

    @Test
    void createVerifiesRepositoryThenStoresCredentialWriteOnly() {
        when(mapper.selectCount(any())).thenReturn(0L);
        when(client.branches(any(), any(), any())).thenReturn(List.of("main"));

        ProjectDtos.View view = service.create(new ProjectDtos.Create("订单服务", REPO, "secret-token", 1, "main"));

        assertTrue(view.credentialConfigured());
        assertEquals("订单服务", view.name());
        verify(client).branches(eq("secret-token"), eq(1), any(GitRepoRef.class));
        ArgumentCaptor<AgentProject> inserted = ArgumentCaptor.forClass(AgentProject.class);
        verify(mapper).insert(inserted.capture());
        assertTrue("secret-token".equals(inserted.getValue().getCredential()));
        assertEquals(0, inserted.getValue().getRevision());
    }

    @Test
    void createRejectsDuplicateRepositoryAddress() {
        when(mapper.selectCount(any())).thenReturn(1L);

        ApiException error = assertThrows(ApiException.class,
                () -> service.create(new ProjectDtos.Create("重复", REPO, null, 1, "main")));

        assertEquals(ErrorCode.CONFLICT, error.errorCode());
        verify(mapper, never()).insert(any(AgentProject.class));
    }

    @Test
    void createReportsUnreachableRepositoryInsteadOfSaving() {
        when(mapper.selectCount(any())).thenReturn(0L);
        when(registry.forRepo(any())).thenThrow(new BusinessException(ResultCode.GIT_HOST_UNSUPPORTED.getCode(), "未接入的仓库宿主"));

        ApiException error = assertThrows(ApiException.class,
                () -> service.create(new ProjectDtos.Create("订单服务", REPO, null, 1, "main")));

        assertEquals(ErrorCode.REPO_UNREACHABLE, error.errorCode());
        verify(mapper, never()).insert(any(AgentProject.class));
    }

    @Test
    void updateKeepsStoredCredentialWhenInputIsBlank() {
        AgentProject current = project(3);
        when(mapper.selectById(any())).thenReturn(current);
        when(mapper.update(any(), any())).thenReturn(1);

        service.update(current.getId(), new ProjectDtos.Update("新名称", "   ", 3));

        verify(mapper).update(org.mockito.ArgumentMatchers.argThat(patch ->
                patch.getCredential() == null && patch.getRevision() == 4), any());
        verify(client, never()).branches(any(), any(), any());
    }

    @Test
    void updateValidatesNewCredentialBeforeReplacingIt() {
        AgentProject current = project(0);
        when(mapper.selectById(any())).thenReturn(current);
        when(mapper.update(any(), any())).thenReturn(1);
        when(client.branches(any(), any(), any())).thenReturn(List.of("main"));

        service.update(current.getId(), new ProjectDtos.Update("新名称", "new-token", 0));

        verify(client).branches(eq("new-token"), eq(1), any(GitRepoRef.class));
        verify(mapper).update(org.mockito.ArgumentMatchers.argThat(patch ->
                "new-token".equals(patch.getCredential())), any());
    }

    @Test
    void updateRejectsStaleRevisionWithoutTouchingTheRow() {
        AgentProject current = project(5);
        when(mapper.selectById(any())).thenReturn(current);

        ApiException error = assertThrows(ApiException.class,
                () -> service.update(current.getId(), new ProjectDtos.Update("名称", null, 4)));

        assertEquals(ErrorCode.REVISION_CONFLICT, error.errorCode());
        verify(mapper, never()).update(any(), any());
    }

    @Test
    void deleteRejectsAProjectWithAnOpenTask() {
        AgentProject current = project(0);
        when(mapper.selectById(any())).thenReturn(current);
        when(taskMapper.selectCount(any())).thenReturn(1L);

        ApiException error = assertThrows(ApiException.class, () -> service.delete(current.getId()));

        assertEquals(ErrorCode.CONFLICT, error.errorCode());
        assertTrue(error.getMessage().contains("进行中"));
        verify(mapper, never()).deleteById(any(Serializable.class));
    }

    @Test
    void deleteLogicallyRemovesAProjectWithoutOpenTasks() {
        AgentProject current = project(0);
        when(mapper.selectById(any())).thenReturn(current);
        when(taskMapper.selectCount(any())).thenReturn(0L);
        when(mapper.deleteById(any(Serializable.class))).thenReturn(1);

        service.delete(current.getId());

        verify(mapper).deleteById(current.getId());
    }

    @Test
    void deleteRejectsAMissingProject() {
        when(mapper.selectById(any())).thenReturn(null);

        ApiException error = assertThrows(ApiException.class,
                () -> service.delete(project(0).getId()));

        assertEquals(ErrorCode.NOT_FOUND, error.errorCode());
        verify(taskMapper, never()).selectCount(any());
    }

    private static AgentProject project(int revision) {
        AgentProject project = new AgentProject();
        project.setId("01J9Z6Q3W4X5Y6Z7A8B9C0D1E2");
        project.setName("订单服务");
        project.setRepoUrl(REPO);
        project.setCredential("old-token");
        project.setCredentialType(1);
        project.setCurrentBranch("main");
        project.setRevision(revision);
        return project;
    }
}
