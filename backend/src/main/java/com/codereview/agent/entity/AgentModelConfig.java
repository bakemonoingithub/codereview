package com.codereview.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 新库模型配置。窗口与输出上限是容量判断输入；验证记录按 {@code revision} 绑定。 */
@Data
@TableName("model_config")
public class AgentModelConfig {

    @TableId(type = IdType.INPUT)
    private String id;
    private String name;
    private String baseUrl;
    private String modelName;
    private String credential;
    private Integer contextWindow;
    private Integer maxOutput;
    private String tokenizer;
    private String protocol;
    private Integer revision;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String createdBy;
    private String updatedBy;
    @TableLogic
    private Integer isDeleted;
}
