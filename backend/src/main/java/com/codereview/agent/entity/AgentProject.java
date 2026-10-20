package com.codereview.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 新库项目。{@code credentialRef} 只写不读，接口不回传凭据。 */
@Data
@TableName("project")
public class AgentProject {

    @TableId(type = IdType.INPUT)
    private String id;
    private String name;
    private String repoUrl;
    private String credentialRef;
    private Integer credentialType;
    private String currentBranch;
    private Integer revision;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String createdBy;
    private String updatedBy;
    @TableLogic
    private Integer isDeleted;
}
