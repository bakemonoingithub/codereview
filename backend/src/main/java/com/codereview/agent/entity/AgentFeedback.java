package com.codereview.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 反馈：主体为 issue 或 result，{@code (subject_kind, subject_id)} 唯一，revision 防覆盖。 */
@Data
@TableName("feedback")
public class AgentFeedback {

    @TableId(type = IdType.INPUT)
    private String id;
    private String subjectKind;
    private String subjectId;
    private String assessment;
    private String comment;
    private Integer revision;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
