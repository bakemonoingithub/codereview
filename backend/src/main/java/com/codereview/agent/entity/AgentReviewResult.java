package com.codereview.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 不可变结果：发布后正文不改；schemaVersion 固定该结果的内容协议。 */
@Data
@TableName("review_result")
public class AgentReviewResult {

    @TableId(type = IdType.INPUT)
    private String id;
    private String taskId;
    private String targetId;
    private String kind;
    private String schemaVersion;
    private String body;
    private String provenance;
    private String completeness;
    private LocalDateTime createdAt;
}
