package com.codereview.git;

import com.codereview.agent.api.ApiException;
import com.codereview.agent.api.ErrorCode;
import com.codereview.agent.db.AgentDatabase;
import com.codereview.agent.entity.AgentProject;
import com.codereview.agent.mapper.AgentProjectMapper;
import com.codereview.agent.repository.RepositoryReadService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static com.codereview.git.GitStubServer.StubResponse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 公共读取服务与真实 Gitea 客户端之间的本地 HTTP 回归，不连接真实宿主或数据库。 */
class GiteaReadBoundaryTest {

    private static final String FIRST_PROJECT = "01J9Z6Q3W4X5Y6Z7A8B9C0D1E2";
    private static final String SECOND_PROJECT = "01J9Z6Q3W4X5Y6Z7A8B9C0D1E3";
    private static final String HEAD = "b7a312e0c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6";
    private static final String PARENT = "12ac80f0a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6";

    private GitStubServer stub;
    private RepositoryReadService service;

    @BeforeEach
    void setUp() throws IOException {
        stub = new GitStubServer();
        stub.start();
        AgentProject first = project(FIRST_PROJECT, "/site-a/o/r");
        AgentProject second = project(SECOND_PROJECT, "/site-b/o/r");
        AgentProjectMapper projects = mock(AgentProjectMapper.class);
        when(projects.selectById(FIRST_PROJECT)).thenReturn(first);
        when(projects.selectById(SECOND_PROJECT)).thenReturn(second);
        AgentDatabase db = mock(AgentDatabase.class);
        when(db.mapper(AgentProjectMapper.class)).thenReturn(projects);

        GiteaProperties props = new GiteaProperties();
        props.setHosts(List.of(GitRepoRef.parse(first.getRepoUrl()).host()));
        GiteaClient client = new GiteaClient(props, new GitCache(16, 16, 30), mock(GiteaGitMirror.class));
        service = new RepositoryReadService(db, new GitHostClientRegistry(List.of(client)));
    }

    @AfterEach
    void tearDown() {
        stub.stop();
    }

    @Test
    void sameHostDifferentSiteRootsDoNotSharePrivateFileCache() {
        stub.respond(exchange -> exchange.getRequestURI().getPath().startsWith("/site-a/")
                ? StubResponse.ok("{\"encoding\":\"base64\",\"content\":\"aGk=\"}")
                : new StubResponse(401, Map.of(), "{}"));

        assertEquals("hi", service.readFile(FIRST_PROJECT, HEAD, "A.java").content());
        assertEquals("hi", service.readFile(FIRST_PROJECT, HEAD, "A.java").content());
        ApiException error = assertThrows(ApiException.class,
                () -> service.readFile(SECOND_PROJECT, HEAD, "A.java"));

        assertEquals(ErrorCode.REPO_UNAUTHORIZED, error.errorCode());
        assertEquals(2, stub.requestUris().size(), "同项目命中缓存，另一个站点必须独立请求");
        assertEquals("/site-b/api/v1/repos/o/r/contents/A.java?ref=" + HEAD, stub.uri(1));
    }

    @Test
    void diffEndpointFailuresKeepTheirClassificationAndAreNotCached() {
        for (int status : new int[]{404, 401, 403, 429, 503}) {
            stub.respond(exchange -> exchange.getRequestURI().getPath().endsWith(".diff")
                    ? new StubResponse(status, Map.of("Retry-After", "30"), "{}")
                    : StubResponse.ok(commitJson()));
            int previousRequests = stub.requestUris().size();

            ApiException error = assertThrows(ApiException.class,
                    () -> service.diff(FIRST_PROJECT, HEAD, "A.java"));

            ErrorCode expected = switch (status) {
                case 404 -> ErrorCode.NOT_FOUND;
                case 401, 403 -> ErrorCode.REPO_UNAUTHORIZED;
                case 429 -> ErrorCode.RATE_LIMITED;
                default -> ErrorCode.REPO_UNAVAILABLE;
            };
            assertEquals(expected, error.errorCode());
            assertEquals(status, ((Map<?, ?>) error.details()).get("status"));
            if (status == 429) {
                assertEquals("30", ((Map<?, ?>) error.details()).get("retryAfter"));
            }
            assertEquals(status == 429 || status >= 500, error.errorCode().retryable());
            assertEquals(previousRequests + 2, stub.requestUris().size(), "失败的提交读取不能进入缓存");
        }
    }

    private AgentProject project(String id, String path) {
        AgentProject project = new AgentProject();
        project.setId(id);
        project.setRepoUrl(stub.base() + path);
        project.setCredential("same-token");
        project.setCredentialType(1);
        project.setRevision(0);
        return project;
    }

    private static String commitJson() {
        return "{\"sha\":\"" + HEAD + "\",\"parents\":[{\"sha\":\"" + PARENT
                + "\"}],\"files\":[{\"filename\":\"A.java\",\"status\":\"modified\"}]}";
    }
}
