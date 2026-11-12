package com.codereview.agent.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

/**
 * PR 自动审查配置、投递处理记录与 Webhook DTO。
 */
public final class PrAutoReviewDtos {

    private PrAutoReviewDtos() {
    }

    public record ReviewerItem(
            @NotNull @Positive Long id,
            @NotBlank String login
    ) {
    }

    public record ConfigUpdate(
            Boolean enabled,
            @Size(max = 200) String targetBranch,
            String modelConfigId,
            String promptId,
            String promptVersionId,
            @Size(max = 500) String reviewBaseUrl,
            String webhookSecret,
            List<@NotNull @Valid ReviewerItem> reviewers,
            Long botActorId,
            @Size(max = 100) String botLogin,
            @Positive Long repoId,
            @Size(max = 500) String repoFullName
    ) {
    }

    public record ConfigView(
            String id,
            String projectId,
            boolean enabled,
            String targetBranch,
            String modelConfigId,
            String promptId,
            String promptVersionId,
            String reviewBaseUrl,
            boolean webhookSecretConfigured,
            List<ReviewerItem> reviewers,
            Long botActorId,
            String botLogin,
            Long repoId,
            String repoFullName,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
    }

    public record DeliveryView(
            String id,
            String hostType,
            String hostInstanceKey,
            String deliveryId,
            LocalDateTime firstReceivedAt,
            LocalDateTime lastReceivedAt,
            int receiveCount,
            String eventName,
            String eventType,
            String eventAction,
            Long repoId,
            String repoFullName,
            Long prNumber,
            Long actorUserId,
            String actorLogin,
            Long sourceCommentId,
            String projectId,
            String triggerKind,
            String targetBranch,
            String decisionState,
            LocalDateTime decidedAt,
            String decisionCode,
            String decisionMessage,
            String businessKey,
            String canonicalRecordId,
            String reviewTaskId,
            String reviewUrl,
            String commentState,
            Long commentId,
            LocalDateTime updatedAt
    ) {
    }

    public record DeliveryDetailView(
            String id,
            String hostType,
            String hostInstanceKey,
            String deliveryId,
            LocalDateTime firstReceivedAt,
            LocalDateTime lastReceivedAt,
            int receiveCount,
            String eventName,
            String eventType,
            String eventAction,
            Long repoId,
            String repoFullName,
            Long prNumber,
            Long actorUserId,
            String actorLogin,
            Long sourceCommentId,
            String payloadSnapshot,
            String projectId,
            String triggerKind,
            String targetBranch,
            String decisionState,
            LocalDateTime decidedAt,
            String decisionCode,
            String decisionMessage,
            String businessKey,
            String canonicalRecordId,
            String taskRequestSnapshot,
            String sourceHead,
            String targetHead,
            String mergeBase,
            Integer textCount,
            Integer binaryCount,
            Integer unreadableCount,
            String reviewTaskId,
            String reviewUrl,
            Long commentActorId,
            String commentBody,
            int commentAttempts,
            LocalDateTime commentLastAttemptAt,
            String commentState,
            Long commentId,
            Integer commentHttpStatus,
            String commentError,
            LocalDateTime commentCheckedAt,
            int revision,
            LocalDateTime leaseUntil,
            LocalDateTime nextAttemptAt,
            LocalDateTime updatedAt
    ) {
    }

    public record WebhookReceiveResult(
            String deliveryId,
            String decisionState,
            String decisionCode,
            String decisionMessage,
            int receiveCount
    ) {
    }
}
