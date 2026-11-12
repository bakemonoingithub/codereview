package com.codereview.agent.api;

import com.codereview.agent.dto.PrAutoReviewDtos;
import com.codereview.agent.service.PrAutoReviewService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class WebhookV2ControllerTest {

    @Mock
    private PrAutoReviewService service;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new WebhookV2Controller(service))
                .setControllerAdvice(new V2ExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("GitHub Webhook 正常受理返回 200")
    void gitHubWebhookAccepted() throws Exception {
        when(service.receiveWebhook(eq("github"), any(), eq("del-001"), eq("pull_request"), any(), eq("sha256=abc"), any()))
                .thenReturn(new PrAutoReviewDtos.WebhookReceiveResult("del-001", "RECEIVED", null, null, 1));

        mvc.perform(post("/api/v2/webhooks/github")
                        .header("X-GitHub-Delivery", "del-001")
                        .header("X-GitHub-Event", "pull_request")
                        .header("X-Hub-Signature-256", "sha256=abc")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"opened\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveryId").value("del-001"))
                .andExpect(jsonPath("$.decisionState").value("RECEIVED"));
    }

    @Test
    void giteaDestinationHostIsNotUsedAsSourceInstance() throws Exception {
        when(service.receiveWebhook(eq("gitea"), isNull(), eq("gta-delivery"), eq("pull_request"),
                eq("pull_request"), eq("fixture-signature"), any()))
                .thenReturn(new PrAutoReviewDtos.WebhookReceiveResult("record", "RECEIVED", null, null, 1));

        mvc.perform(post("/webhooks/gitea")
                        .header("Host", "review.example.com")
                        .header("X-Gitea-Delivery", "gta-delivery")
                        .header("X-Gitea-Event", "pull_request")
                        .header("X-Gitea-Event-Type", "pull_request")
                        .header("X-Gitea-Signature", "fixture-signature")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"opened\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decisionState").value("RECEIVED"));
    }

    @Test
    void missingNativeDeliveryHeaderReturns400() throws Exception {
        when(service.receiveWebhook(eq("gitea"), eq("https://git.example/gitea"), isNull(), eq("pull_request"),
                eq("pull_request"), eq("fixture-signature"), any()))
                .thenReturn(new PrAutoReviewDtos.WebhookReceiveResult("record", "REJECTED", "DELIVERY_ID_INVALID", null, 1));

        mvc.perform(post("/api/v2/webhooks/gitea")
                        .param("instance", "https://git.example/gitea")
                        .header("X-GitHub-Delivery", "wrong-host-delivery")
                        .header("X-Gitea-Event", "pull_request")
                        .header("X-Gitea-Event-Type", "pull_request")
                        .header("X-Gitea-Signature", "fixture-signature")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"opened\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.decisionCode").value("DELIVERY_ID_INVALID"));
    }

    @Test
    @DisplayName("Gitea Webhook 验签拒绝返回 401 UNAUTHORIZED")
    void giteaWebhookRejectedReturns401() throws Exception {
        when(service.receiveWebhook(eq("gitea"), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PrAutoReviewDtos.WebhookReceiveResult(null, "REJECTED", "SIGNATURE_INVALID", "Webhook 验签失败", 1));

        mvc.perform(post("/webhooks/gitea")
                        .header("X-Gitea-Delivery", "del-gta-bad")
                        .header("X-Gitea-Event", "pull_request")
                        .header("X-Gitea-Signature", "wrong-sig")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"opened\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.decisionState").value("REJECTED"))
                .andExpect(jsonPath("$.decisionCode").value("SIGNATURE_INVALID"));
    }
}
