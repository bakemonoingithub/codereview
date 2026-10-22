package com.codereview.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 一次实际工具执行；参数、envelope 与材料 ID 原样保存，便于调用记录阅读与来源引用。 */
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
