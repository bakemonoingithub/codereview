package com.codereview.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 任务级请求额度。每次真实尝试在发请求前原子加一，硬上限由条件更新保证。 */
@Data
@TableName("task_quota")
public class AgentTaskQuota {

    @TableId(type = IdType.INPUT)
    private String taskId;
    private Integer hardLimit;
    private Integer reserved;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
