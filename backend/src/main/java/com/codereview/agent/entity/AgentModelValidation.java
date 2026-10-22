package com.codereview.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 模型验证记录：同一配置修订只认最新一条，修订变化即失效。 */
@Data
@TableName("model_validation")
public class AgentModelValidation {

    @TableId(type = IdType.INPUT)
    private String id;
    private String modelConfigId;
    private Integer configRevision;
    private String scope;
    private String result;
    private String detailJson;
    private LocalDateTime createdAt;
}
