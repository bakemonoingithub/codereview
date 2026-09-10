package com.codereview.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * issue 标记（误报 / 已采纳），用于准确率复核。
 * <p>
 * {@code markValue}：0 未标记（撤销后的状态）/ 1 误报 / 2 已采纳。
 * 撤销标记写回 0 而非删行——逻辑删除会让唯一键仍被占用，导致同一条 issue 无法重新标记。
 */
@Data
@TableName("issue_mark")
public class IssueMark {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long recordId;
    private String unitPath;
    private Integer issueIndex;
    private String unitName;
    private Integer markValue;
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
