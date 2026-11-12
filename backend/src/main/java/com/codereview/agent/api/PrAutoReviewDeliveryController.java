package com.codereview.agent.api;

import com.codereview.agent.dto.PrAutoReviewDtos;
import com.codereview.agent.service.PrAutoReviewService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 项目 Webhook 投递处理记录查询接口。
 */
@RestController
@RequestMapping("/api/v2/projects/{projectId}/auto-review-deliveries")
@ConditionalOnProperty(prefix = "agent.datasource", name = "url")
public class PrAutoReviewDeliveryController {

    private final PrAutoReviewService service;

    public PrAutoReviewDeliveryController(PrAutoReviewService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<PrAutoReviewDtos.DeliveryView> list(@PathVariable String projectId,
                                                           @RequestParam(required = false) String eventName,
                                                           @RequestParam(required = false) String decisionState,
                                                           @RequestParam(required = false) String decisionCode,
                                                           @RequestParam(required = false) String commentState,
                                                           @RequestParam(required = false) Long prNumber,
                                                           @RequestParam(required = false) String q,
                                                           @RequestParam(required = false) String cursor,
                                                           @RequestParam(required = false) Integer limit,
                                                           @RequestParam(required = false) Integer pageNum,
                                                           @RequestParam(required = false) Integer pageSize) {
        return service.listDeliveries(projectId, eventName, decisionState, decisionCode, commentState,
                prNumber, q, cursor, limit, pageNum, pageSize);
    }

    @GetMapping("/{id}")
    public PrAutoReviewDtos.DeliveryDetailView detail(@PathVariable String projectId,
                                                     @PathVariable String id) {
        return service.getDelivery(projectId, id);
    }
}
