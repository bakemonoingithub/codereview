package com.codereview.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 提示词。正文不进本表，只在 {@code prompt_version} 里按版本保存。 */
@Data
@TableName("prompt")
public class AgentPrompt {

    @TableId(type = IdType.INPUT)
    private String id;
    private String name;
    private String description;
    private String tags;
    private String purpose;
    private String currentVersionId;
    private Integer revision;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String createdBy;
    private String updatedBy;
    @TableLogic
    private Integer isDeleted;
}
