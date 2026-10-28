package com.codereview.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/** 不复制正文，ordinal 始终指向发布时的原始数组顺序。 */
@Data
@TableName("review_result_item")
public class AgentReviewResultItem {
    @TableId(type = IdType.INPUT)
    private String id;
    private String resultId;
    private String kind;
    private Integer ordinal;
    private LocalDateTime createdAt;
}
