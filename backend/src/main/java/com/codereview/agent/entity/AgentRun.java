package com.codereview.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Agent 执行进度：每个任务一行，用于进度读取与重启收敛，不表示支持恢复原会话。 */
@Data
@TableName("agent_run")
public class AgentRun {

    @TableId(type = IdType.INPUT)
    private String taskId;
    private Integer turnNo;
    private Integer toolAttempts;
    private Integer networkAttempts;
    private String currentAction;
    private String coveredJson;
    private String state;
    private String stopReason;
    private Integer revision;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
