package com.codereview.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Webhook 投递处理记录。
 */
@Data
@TableName("pr_auto_review_delivery")
public class PrAutoReviewDelivery {

    @TableId(type = IdType.INPUT)
    private String id;
    private String hostType;
    private String hostInstanceKey;
    private String deliveryId;
    private LocalDateTime firstReceivedAt;
    private LocalDateTime lastReceivedAt;
    private Integer receiveCount;
    private String eventName;
    private String eventType;
    private String eventAction;
    private Long repoId;
    private String repoFullName;
    private Long prNumber;
    private Long actorUserId;
    private String actorLogin;
    private Long sourceCommentId;
    private String payloadSnapshot;
    private String projectId;
    private String triggerKind;
    private String targetBranch;
    private String decisionState;
    private LocalDateTime decidedAt;
    private String decisionCode;
    private String decisionMessage;
    private String businessKey;
    private String canonicalRecordId;
    private String taskRequestSnapshot;
    private String sourceHead;
    private String targetHead;
    private String mergeBase;
    private Integer textCount;
    private Integer binaryCount;
    private Integer unreadableCount;
    private String reviewTaskId;
    private String reviewUrl;
    private Long commentActorId;
    private String commentBody;
    private Integer commentAttempts;
    private LocalDateTime commentLastAttemptAt;
    private String commentState;
    private Long commentId;
    private Integer commentHttpStatus;
    private String commentError;
    private LocalDateTime commentCheckedAt;
    private Integer revision;
    private LocalDateTime leaseUntil;
    private LocalDateTime nextAttemptAt;
    private LocalDateTime updatedAt;
}
