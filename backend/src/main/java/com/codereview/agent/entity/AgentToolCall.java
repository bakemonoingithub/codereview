package com.codereview.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 一次工具调用尝试；包括已执行调用与未执行的整批拒绝记录。 */
@Data
@TableName("agent_tool_call")
public class AgentToolCall {

    @TableId(type = IdType.INPUT)
    private String id;
    private String taskId;
    private String modelCallId;
    private Integer turnNo;
    private Integer ordinal;
    private String providerToolCallId;
    private String toolName;
    private String arguments;
    private String envelope;
    private String materialIds;
    private String status;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
