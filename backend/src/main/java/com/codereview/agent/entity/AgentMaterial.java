package com.codereview.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 材料：保存实际用到或读取到的固定版本内容，side=FILE/BEFORE/AFTER 不可互换。 */
@Data
@TableName("material")
public class AgentMaterial {

    @TableId(type = IdType.INPUT)
    private String id;
    private String projectId;
    private String sha;
    private String path;
    private String side;
    private Integer startLine;
    private Integer endLine;
    private String content;
    private String contentHash;
    private String completeness;
    private String diagnostic;
    private LocalDateTime createdAt;
}
