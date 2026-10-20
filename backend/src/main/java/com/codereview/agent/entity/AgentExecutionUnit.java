package com.codereview.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 执行单元：整文件也有单元；重规划生成新 generation，旧代不能发布结果。 */
@Data
@TableName("execution_unit")
public class AgentExecutionUnit {

    @TableId(type = IdType.INPUT)
    private String id;
    private String targetId;
    private Integer generation;
    private Integer ordinal;
    private String ranges;
    private String state;
    private Integer revision;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
