package com.codereview.agent.api;

import com.codereview.agent.dto.PrAutoReviewDtos;
import com.codereview.agent.service.PrAutoReviewService;
import com.codereview.git.GitUserRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class PrAutoReviewControllerTest {

    private static final String PROJECT_ID = "01PROJECT00000000000000001";

    @Mock
    private PrAutoReviewService service;

    private MockMvc configMvc;
    private MockMvc deliveryMvc;

    @BeforeEach
    void setUp() {
        configMvc = MockMvcBuilders.standaloneSetup(new PrAutoReviewController(service))
                .setControllerAdvice(new V2ExceptionHandler())
                .build();
        deliveryMvc = MockMvcBuilders.standaloneSetup(new PrAutoReviewDeliveryController(service))
                .setControllerAdvice(new V2ExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("读取配置：返回项目 PR 自动审查配置")
    void getConfig() throws Exception {
        PrAutoReviewDtos.ConfigView view = new PrAutoReviewDtos.ConfigView(
                "01CONFIG00000000000000001",
                PROJECT_ID,
                true,
                "main",
                null,
                null,
                null,
                "https://review.example.com",
                true,
                List.of(new PrAutoReviewDtos.ReviewerItem(101L, "li.ming")),
                901L,
                "code-review-bot",
                31L,
                "engineering/aiCodeReview",
                LocalDateTime.now(),
                LocalDateTime.now()
        );

        when(service.getConfig(PROJECT_ID)).thenReturn(view);

        configMvc.perform(get("/api/v2/projects/" + PROJECT_ID + "/auto-review-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.targetBranch").value("main"))
                .andExpect(jsonPath("$.reviewers[0].login").value("li.ming"));
    }

    @Test
    @DisplayName("更新配置：保存成功返回更新后视图")
    void updateConfig() throws Exception {
        PrAutoReviewDtos.ConfigView view = new PrAutoReviewDtos.ConfigView(
                "01CONFIG00000000000000001",
                PROJECT_ID,
                true,
                "dev",
                null,
                null,
                null,
                "https://review.example.com",
                true,
                List.of(),
                901L,
                "code-review-bot",
                31L,
                "engineering/aiCodeReview",
                LocalDateTime.now(),
                LocalDateTime.now()
        );

        when(service.updateConfig(eq(PROJECT_ID), any())).thenReturn(view);

        configMvc.perform(put("/api/v2/projects/" + PROJECT_ID + "/auto-review-config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"targetBranch\":\"dev\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetBranch").value("dev"));
    }

    @Test
    @DisplayName("审核者查询：按用户名返回身份引用")
    void lookupReviewer() throws Exception {
        when(service.lookupReviewer(PROJECT_ID, "li.ming"))
                .thenReturn(new GitUserRef(101L, "li.ming", "Li Ming", "https://avatar.com/101"));

        configMvc.perform(get("/api/v2/projects/" + PROJECT_ID + "/auto-review-config/reviewers/lookup")
                        .param("username", "li.ming"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(101))
                .andExpect(jsonPath("$.login").value("li.ming"));
    }

    @Test
    @DisplayName("查询投递记录详情：返回单条处理记录")
    void getDeliveryDetail() throws Exception {
        PrAutoReviewDtos.DeliveryDetailView detail = new PrAutoReviewDtos.DeliveryDetailView(
                "01DEL000000000000000000001",
                "github",
                "github.com",
                "gh-del-1",
                LocalDateTime.now(),
                LocalDateTime.now(),
                1,
                "pull_request",
                null,
                "opened",
                31L,
                "engineering/aiCodeReview",
                15L,
                201L,
                "developer",
                null,
                "{}",
                PROJECT_ID,
                "FIRST",
                "main",
                "RECEIVED",
                LocalDateTime.now(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                0,
                null,
                "NONE",
                null,
                null,
                null,
                null,
                0,
                null,
                null,
                LocalDateTime.now()
        );

        when(service.getDelivery(PROJECT_ID, "01DEL000000000000000000001")).thenReturn(detail);

        deliveryMvc.perform(get("/api/v2/projects/" + PROJECT_ID + "/auto-review-deliveries/01DEL000000000000000000001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("01DEL000000000000000000001"))
                .andExpect(jsonPath("$.decisionState").value("RECEIVED"));
    }

    @Test
    @DisplayName("查询投递记录列表：支持按事件、决定、原因码、回写状态、PR 编号及关键字筛选")
    void listDeliveriesWithFilters() throws Exception {
        PrAutoReviewDtos.DeliveryView view = new PrAutoReviewDtos.DeliveryView(
                "01DEL000000000000000000002",
                "gitea",
                "http://localhost:3000",
                "gta-del-2",
                LocalDateTime.now(),
                LocalDateTime.now(),
                1,
                "issue_comment",
                "pull_request_comment",
                "edited",
                31L,
                "engineering/aiCodeReview",
                13L,
                101L,
                "li.ming",
                3213L,
                PROJECT_ID,
                null,
                null,
                "SKIPPED",
                LocalDateTime.now(),
                "ACTION_IGNORED",
                "编辑旧评论不触发复审",
                null,
                null,
                null,
                null,
                "NONE",
                null,
                LocalDateTime.now()
        );
        when(service.listDeliveries(eq(PROJECT_ID), eq("pull_request_comment"), eq("SKIPPED"),
                eq("ACTION_IGNORED"), eq("NONE"), eq(13L), eq("编辑旧评论"), any(), any(), any(), any()))
                .thenReturn(new PageResponse<>(List.of(view), null, false));

        deliveryMvc.perform(get("/api/v2/projects/" + PROJECT_ID + "/auto-review-deliveries")
                        .param("eventName", "pull_request_comment")
                        .param("decisionState", "SKIPPED")
                        .param("decisionCode", "ACTION_IGNORED")
                        .param("commentState", "NONE")
                        .param("prNumber", "13")
                        .param("q", "编辑旧评论"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value("01DEL000000000000000000002"))
                .andExpect(jsonPath("$.items[0].decisionCode").value("ACTION_IGNORED"))
                .andExpect(jsonPath("$.items[0].decisionMessage").value("编辑旧评论不触发复审"));
    }

    @Test
    @DisplayName("查询项目投递事件集合：返回已收到的事件原值列表")
    void listDeliveryEvents() throws Exception {
        when(service.listDeliveryEvents(PROJECT_ID))
                .thenReturn(List.of("pull_request", "pull_request_comment", "push"));

        deliveryMvc.perform(get("/api/v2/projects/" + PROJECT_ID + "/auto-review-deliveries/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("pull_request"))
                .andExpect(jsonPath("$[1]").value("pull_request_comment"))
                .andExpect(jsonPath("$[2]").value("push"));
    }
}
