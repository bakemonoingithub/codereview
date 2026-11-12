package com.codereview.agent.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Constants;
import com.codereview.agent.entity.PrAutoReviewDelivery;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface PrAutoReviewDeliveryMapper extends BaseMapper<PrAutoReviewDelivery> {

    // 先关联原记录，再按相同的字段筛选、分页和展示。原记录中的空值也是当前结果。
    String DELIVERY_OUTCOME = """
            FROM (
                SELECT d.id, d.host_type, d.host_instance_key, d.delivery_id,
                       d.first_received_at, d.last_received_at, d.receive_count,
                       d.event_name, d.event_type, d.event_action, d.repo_id, d.repo_full_name,
                       d.pr_number, d.actor_user_id, d.actor_login, d.source_comment_id, d.payload_snapshot,
                       d.project_id, d.trigger_kind, d.decision_state, d.decided_at, d.decision_code, d.decision_message,
                       d.business_key, d.canonical_record_id, d.task_request_snapshot,
                       d.revision, d.lease_until, d.next_attempt_at, d.updated_at,
                       CASE WHEN c.id IS NULL THEN d.target_branch ELSE c.target_branch END AS target_branch,
                       CASE WHEN c.id IS NULL THEN d.source_head ELSE c.source_head END AS source_head,
                       CASE WHEN c.id IS NULL THEN d.target_head ELSE c.target_head END AS target_head,
                       CASE WHEN c.id IS NULL THEN d.merge_base ELSE c.merge_base END AS merge_base,
                       CASE WHEN c.id IS NULL THEN d.text_count ELSE c.text_count END AS text_count,
                       CASE WHEN c.id IS NULL THEN d.binary_count ELSE c.binary_count END AS binary_count,
                       CASE WHEN c.id IS NULL THEN d.unreadable_count ELSE c.unreadable_count END AS unreadable_count,
                       CASE WHEN c.id IS NULL THEN d.review_task_id ELSE c.review_task_id END AS review_task_id,
                       CASE WHEN c.id IS NULL THEN d.review_url ELSE c.review_url END AS review_url,
                       CASE WHEN c.id IS NULL THEN d.comment_actor_id ELSE c.comment_actor_id END AS comment_actor_id,
                       CASE WHEN c.id IS NULL THEN d.comment_body ELSE c.comment_body END AS comment_body,
                       CASE WHEN c.id IS NULL THEN d.comment_attempts ELSE c.comment_attempts END AS comment_attempts,
                       CASE WHEN c.id IS NULL THEN d.comment_last_attempt_at ELSE c.comment_last_attempt_at END AS comment_last_attempt_at,
                       CASE WHEN c.id IS NULL THEN d.comment_state ELSE c.comment_state END AS comment_state,
                       CASE WHEN c.id IS NULL THEN d.comment_id ELSE c.comment_id END AS comment_id,
                       CASE WHEN c.id IS NULL THEN d.comment_http_status ELSE c.comment_http_status END AS comment_http_status,
                       CASE WHEN c.id IS NULL THEN d.comment_error ELSE c.comment_error END AS comment_error,
                       CASE WHEN c.id IS NULL THEN d.comment_checked_at ELSE c.comment_checked_at END AS comment_checked_at
                FROM pr_auto_review_delivery d
                LEFT JOIN pr_auto_review_delivery c ON c.id = d.canonical_record_id AND c.project_id = d.project_id
            ) AS delivery_outcome
            """;

    @Select("SELECT * " + DELIVERY_OUTCOME + " ${ew.customSqlSegment}")
    List<PrAutoReviewDelivery> selectWithOutcome(
            @Param(Constants.WRAPPER) LambdaQueryWrapper<PrAutoReviewDelivery> wrapper);

    @Select("SELECT COUNT(*) " + DELIVERY_OUTCOME + " ${ew.customSqlSegment}")
    long countWithOutcome(@Param(Constants.WRAPPER) LambdaQueryWrapper<PrAutoReviewDelivery> wrapper);

    @Select("""
            SELECT DISTINCT COALESCE(event_type, event_name)
            FROM pr_auto_review_delivery
            WHERE project_id = #{projectId}
              AND COALESCE(event_type, event_name) IS NOT NULL
            ORDER BY COALESCE(event_type, event_name) ASC
            """)
    List<String> selectDistinctEvents(@Param("projectId") String projectId);

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
