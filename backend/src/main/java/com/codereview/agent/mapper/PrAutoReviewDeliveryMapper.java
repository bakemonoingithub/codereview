package com.codereview.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.codereview.agent.entity.PrAutoReviewDelivery;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PrAutoReviewDeliveryMapper extends BaseMapper<PrAutoReviewDelivery> {

    /** 重投只更新接收信息。原载荷、审查决定和处理版本由首次投递及后续处理维护。 */
    @Insert("""
            INSERT INTO pr_auto_review_delivery (
                id, host_type, host_instance_key, delivery_id, first_received_at, last_received_at,
                event_name, event_type, event_action, repo_id, repo_full_name, pr_number, actor_user_id,
                actor_login, source_comment_id, payload_snapshot, project_id, trigger_kind, target_branch,
                source_head, decision_state, decided_at, decision_code, decision_message, updated_at
            ) VALUES (
                #{id}, #{hostType}, #{hostInstanceKey}, #{deliveryId}, #{firstReceivedAt}, #{lastReceivedAt},
                #{eventName}, #{eventType}, #{eventAction}, #{repoId}, #{repoFullName}, #{prNumber}, #{actorUserId},
                #{actorLogin}, #{sourceCommentId}, #{payloadSnapshot}, #{projectId}, #{triggerKind}, #{targetBranch},
                #{sourceHead}, #{decisionState}, #{decidedAt}, #{decisionCode}, #{decisionMessage}, #{updatedAt}
            ) ON DUPLICATE KEY UPDATE
                receive_count = receive_count + 1,
                last_received_at = GREATEST(last_received_at, #{lastReceivedAt})
            """)
    int upsertReceived(PrAutoReviewDelivery delivery);
}
