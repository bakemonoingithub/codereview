package com.codereview.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.codereview.agent.entity.AgentReviewTask;

/** 新库 AgentReviewTask 的访问入口；SQL 由 MyBatis-Plus 生成，条件查询在服务层构造。 */
public interface AgentReviewTaskMapper extends BaseMapper<AgentReviewTask> {
    @org.apache.ibatis.annotations.Select("SELECT * FROM review_task WHERE id = #{id} FOR UPDATE")
    AgentReviewTask selectIncludingDeletedForUpdate(@org.apache.ibatis.annotations.Param("id") String id);

    @org.apache.ibatis.annotations.Update("UPDATE review_task SET is_deleted=1, deleted_at=UTC_TIMESTAMP(3), "
            + "updated_at=UTC_TIMESTAMP(3), revision=revision+1 WHERE id=#{id} AND revision=#{revision} "
            + "AND is_deleted=0 AND state IN ('SUCCEEDED','PARTIAL','FAILED','CANCELLED')")
    int markDeleted(@org.apache.ibatis.annotations.Param("id") String id,
                    @org.apache.ibatis.annotations.Param("revision") int revision);

    @org.apache.ibatis.annotations.Select("SELECT * FROM review_task WHERE project_id=#{projectId} "
            + "AND idempotency_key=#{key} AND is_deleted=1 LIMIT 1")
    AgentReviewTask selectDeletedByKey(@org.apache.ibatis.annotations.Param("projectId") String projectId,
                                     @org.apache.ibatis.annotations.Param("key") String key);

}