package com.codereview.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("review_record")
public class ReviewRecord {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long projectId;
    private Long strategyId;
    private String strategySnapshotJson;
    private String branch;
    private String commitSha;
    private String scopeJson;
    private Integer status;
    private Integer progress;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private String resultJson;
    /**
     * 失败原因（有界，写入前由 {@code ReviewExecutor} 截断到列宽）。
     *
     * <p>{@code updateStrategy = IGNORED} 是**有意**的：默认的 NOT_NULL 策略下
     * {@code setErrorMessage(null)} 等于"根本不更新这一列"，于是重审成功后
     * 上一次的失败原因会永远留在库里（界面就会显示"成功 + 失败原因"）。
     * 所有更新路径都是"先 selectById 把实体读全再 updateById"，所以"总是写这一列"
     * 不会误伤：读出来是 NULL 就写回 NULL。
     */
    @TableField(updateStrategy = FieldStrategy.IGNORED)
    private String errorMessage;
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
