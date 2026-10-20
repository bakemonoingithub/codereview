package com.codereview.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 任务目标：一个文件一条；差异目标保留 base/head 与旧路径，文件目标只取 head。 */
@Data
@TableName("task_target")
public class AgentTaskTarget {

    @TableId(type = IdType.INPUT)
    private String id;
    private String taskId;
    private Integer ordinal;
    private String path;
    private String oldPath;
    private String baseSha;
    private String headSha;
    private String note;
    private String state;
    private String errorCode;
    private Integer revision;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
