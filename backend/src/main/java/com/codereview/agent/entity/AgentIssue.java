package com.codereview.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 问题：只在问题结果内创建，id 发布后稳定。locations/sourceUnits 为 JSON 文本。 */
@Data
@TableName("issue")
public class AgentIssue {

    @TableId(type = IdType.INPUT)
    private String id;
    private String resultId;
    private Integer ordinal;
    private String category;
    private String severity;
    private String title;
    private String reason;
    private String suggestion;
    private String locations;
    private String sourceUnits;
}
