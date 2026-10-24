package com.codereview.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 提示词版本：非当前历史版本不可改，实际调用另存快照。 */
@Data
@TableName("prompt_version")
public class AgentPromptVersion {

    @TableId(type = IdType.INPUT)
    private String id;
    private String promptId;
    private Integer versionNo;
    private String content;
    private LocalDateTime createdAt;
}
