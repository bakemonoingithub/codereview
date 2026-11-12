package com.codereview.agent.api;

import com.codereview.agent.dto.PrAutoReviewDtos;
import com.codereview.agent.service.PrAutoReviewService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 双宿主 Webhook 统一接收入口（GitHub 与 Gitea 1.16.1）。
 */
@RestController
@RequestMapping({"/api/v2/webhooks", "/webhooks"})
@ConditionalOnProperty(prefix = "agent.datasource", name = "url")
public class WebhookV2Controller {

    private final PrAutoReviewService service;

    public WebhookV2Controller(PrAutoReviewService service) {
        this.service = service;
    }

    @PostMapping("/{hostType}")
    public ResponseEntity<PrAutoReviewDtos.WebhookReceiveResult> receive(
            @PathVariable String hostType,
            @RequestParam(value = "instance", required = false) String instance,
            @RequestHeader(value = "X-GitHub-Delivery", required = false) String gitHubDelivery,
            @RequestHeader(value = "X-Gitea-Delivery", required = false) String giteaDelivery,
            @RequestHeader(value = "X-GitHub-Event", required = false) String gitHubEvent,
            @RequestHeader(value = "X-Gitea-Event", required = false) String giteaEvent,
            @RequestHeader(value = "X-Gitea-Event-Type", required = false) String giteaEventType,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String gitHubSig,
            @RequestHeader(value = "X-Gitea-Signature", required = false) String giteaSig,
            @RequestBody byte[] rawPayload) {

        boolean isGitHub = "github".equalsIgnoreCase(hostType);
        String deliveryId = isGitHub ? gitHubDelivery : giteaDelivery;
        String eventName = isGitHub ? gitHubEvent : giteaEvent;
        String eventType = isGitHub ? null : giteaEventType;
        String signatureHeader = isGitHub ? gitHubSig : giteaSig;

        PrAutoReviewDtos.WebhookReceiveResult result = service.receiveWebhook(
                hostType,
                instance,
                deliveryId,
                eventName,
                eventType,
                signatureHeader,
                rawPayload
        );

        if ("REJECTED".equals(result.decisionState())) {
            HttpStatus status = "SIGNATURE_INVALID".equals(result.decisionCode())
                    ? HttpStatus.UNAUTHORIZED : HttpStatus.BAD_REQUEST;
            return ResponseEntity.status(status).body(result);
        }
        return ResponseEntity.ok(result);
    }
}
