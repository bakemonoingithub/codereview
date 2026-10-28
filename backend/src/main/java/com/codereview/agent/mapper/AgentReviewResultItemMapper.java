package com.codereview.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.codereview.agent.entity.AgentReviewResult;
import com.codereview.agent.entity.AgentReviewResultItem;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface AgentReviewResultItemMapper extends BaseMapper<AgentReviewResultItem> {
    /** 同一结果的回填串行化；锁后读取使用当前事务视图。 */
    @Select("SELECT * FROM review_result WHERE id = #{id} FOR UPDATE")
    AgentReviewResult lockResult(@Param("id") String id);
}
