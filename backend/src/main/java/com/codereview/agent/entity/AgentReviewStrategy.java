package com.codereview.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 审查策略：专项类型 + 关联模型 + 可选通用规则提示词，不再用 params_json 藏关联。 */
@Data
@TableName("review_strategy")
public class AgentReviewStrategy {

    @TableId(type = IdType.INPUT)
    private String id;
    private String name;
    private String specialistType;
    private String modelConfigId;
    private String promptId;
    private Integer revision;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String createdBy;
    private String updatedBy;
    @TableLogic
    private Integer isDeleted;
}
