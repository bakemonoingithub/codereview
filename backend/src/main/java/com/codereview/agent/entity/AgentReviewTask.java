package com.codereview.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 任务：一次发起一条；终态只经带 revision 的条件更新写入。 */
@Data
@TableName("review_task")
public class AgentReviewTask {

    @TableId(type = IdType.INPUT)
    private String id;
    private String projectId;
    private String route;
    private String specialistType;
    private String state;
    private String stopReason;
    private String inputSnapshot;
    private String configSnapshot;
    private Integer revision;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime deadlineAt;
    private LocalDateTime cancelRequestedAt;
}
