package com.codereview.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.codereview.agent.entity.AgentTaskQuota;

/** 任务额度映射器；原子预留在服务层用条件更新完成。 */
public interface AgentTaskQuotaMapper extends BaseMapper<AgentTaskQuota> {
}
