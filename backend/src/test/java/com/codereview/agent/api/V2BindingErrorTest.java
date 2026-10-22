package com.codereview.agent.api;

import com.codereview.agent.repository.RepositoryReadService;
import com.codereview.agent.service.AgentModelService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** v2 的输入错误必须是 400，而不是兜底的 500。 */
class V2BindingErrorTest {

    private static final String PROJECT_ID = "01J9Z6Q3W4X5Y6Z7A8B9C0D1E2";

    @Test
    void missingQueryParameterIsABadRequest() throws Exception {
        MockMvc mvc = MockMvcBuilders
                .standaloneSetup(new RepositoryV2Controller(mock(RepositoryReadService.class)))
                .setControllerAdvice(new V2ExceptionHandler())
                .build();

        mvc.perform(get("/api/v2/projects/{projectId}/files", PROJECT_ID).param("path", "A.java"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void malformedJsonIsABadRequest() throws Exception {
        MockMvc mvc = MockMvcBuilders
                .standaloneSetup(new ModelV2Controller(mock(AgentModelService.class)))
                .setControllerAdvice(new V2ExceptionHandler())
                .build();

        mvc.perform(post("/api/v2/models")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
