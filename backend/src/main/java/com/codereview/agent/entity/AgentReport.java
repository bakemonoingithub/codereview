package com.codereview.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 报告：独立生命周期；frozenInput 是生成前冻结的全部输入，后续配置与源码变化不替换。 */
@Data
@TableName("report")
public class AgentReport {

    @TableId(type = IdType.INPUT)
    private String id;
    private String title;
    private String state;
    private String modelSnapshot;
    private String instructionSnapshot;
    private String frozenInput;
    private String body;
    private String errorCode;
    private String errorMessage;
    private Integer revision;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
