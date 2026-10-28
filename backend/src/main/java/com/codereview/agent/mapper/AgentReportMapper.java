package com.codereview.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.codereview.agent.entity.AgentReport;

/** 新库 AgentReport 的访问入口；SQL 由 MyBatis-Plus 生成，条件查询在服务层构造。 */
public interface AgentReportMapper extends BaseMapper<AgentReport> {
    @org.apache.ibatis.annotations.Select("SELECT * FROM report WHERE id = #{id} FOR UPDATE")
    AgentReport selectIncludingDeletedForUpdate(@org.apache.ibatis.annotations.Param("id") String id);

    @org.apache.ibatis.annotations.Update("UPDATE report SET is_deleted=1, deleted_at=UTC_TIMESTAMP(3), "
            + "updated_at=UTC_TIMESTAMP(3), revision=revision+1 WHERE id=#{id} AND revision=#{revision} "
            + "AND is_deleted=0 AND state IN ('INPUT_FROZEN','SUCCEEDED','FAILED')")
    int markDeleted(@org.apache.ibatis.annotations.Param("id") String id,
                    @org.apache.ibatis.annotations.Param("revision") int revision);

}