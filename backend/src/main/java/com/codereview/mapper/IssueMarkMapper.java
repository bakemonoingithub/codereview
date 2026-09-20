package com.codereview.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.codereview.entity.IssueMark;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

public interface IssueMarkMapper extends BaseMapper<IssueMark> {

    /**
     * 物理删除一行标记（真删除）。
     * <p>
     * 不能用 {@code deleteById}：`IssueMark` 带 {@code @TableLogic}，且 `application.yml` 全局配了
     * `logic-delete-field: isDeleted`，框架会把删除改写成 {@code UPDATE issue_mark SET is_deleted = 1}
     * —— 行还在，唯一键 `uk_record_unit_issue` 仍被占用，这正是"撤销后无法重新标记 / 二次标记撞 1062"的根因。
     * 自定义 {@code @Delete} 语句不经过框架的逻辑删除注入，因此是真删除。
     * <p>
     * 注意：只给"撤销标记"这一处用。删项目时的级联清理（{@code ProjectService}）**仍然走逻辑删除**，
     * 以保留"项目逻辑删除后数据仍可恢复"这一既有性质。
     */
    @Delete("DELETE FROM issue_mark WHERE id = #{id}")
    int deletePhysically(@Param("id") Long id);
}
