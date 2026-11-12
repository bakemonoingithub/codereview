package com.codereview.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 项目 PR 自动审查配置。
 */
@Data
@TableName("pr_auto_review_config")
public class PrAutoReviewConfig {

    @TableId(type = IdType.INPUT)
    private String id;
    private String projectId;
    private Integer enabled;
    private String targetBranch;
    private String modelConfigId;
    /** PUT 省略两个提示词字段时切回默认规则，需清除已保存的版本。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String promptId;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String promptVersionId;
    private String reviewBaseUrl;
    private String webhookSecret;
    private String reviewers;
    private Long botActorId;
    private String botLogin;
    private Long repoId;
    private String repoFullName;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
