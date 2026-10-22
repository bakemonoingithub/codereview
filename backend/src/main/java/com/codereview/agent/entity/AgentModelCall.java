package com.codereview.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 一次真实网络尝试一条；正文先落盘再发请求，历史不回读模板重建。 */
@Data
@TableName("model_call")
public class AgentModelCall {

    @TableId(type = IdType.INPUT)
    private String id;
    private String taskId;
    /** 报告调用的稳定归属；任务调用为空。 */
    private String reportId;
    private String targetId;
    private String unitId;
    private String purpose;
    private Integer attempt;
    private Integer turnNo;
    private String state;
    private String requestBody;
    private String responseBody;
    private String errorCode;
    private String errorMessage;
    private String usageJson;
    private String adapterVersion;
    private String estimateMethod;
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
