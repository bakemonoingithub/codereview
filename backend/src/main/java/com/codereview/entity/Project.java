package com.codereview.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("project")
public class Project {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String name;
    private String giteaUrl;
    /**
     * 仓库访问令牌/账号密码。**只写不读**：创建时提交，但绝不随响应回传浏览器。
     * 对照 {@link ModelConfig} 的 token 处理 —— 后者一直是 WRITE_ONLY，此处原先漏了，
     * 导致 `GET /api/projects` 每次列表都把仓库凭据明文发给前端。
     */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String credential;
    private Integer credentialType;
    private String currentBranch;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updatedBy;
    @TableLogic
    private Integer isDeleted;
}
