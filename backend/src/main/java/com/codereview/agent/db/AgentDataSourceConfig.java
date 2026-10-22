package com.codereview.agent.db;

import com.codereview.agent.mapper.AgentCallMaterialMapper;
import com.codereview.agent.mapper.AgentExecutionUnitMapper;
import com.codereview.agent.mapper.AgentFeedbackMapper;
import com.codereview.agent.mapper.AgentIssueMapper;
import com.codereview.agent.mapper.AgentMaterialMapper;
import com.codereview.agent.mapper.AgentModelCallMapper;
import com.codereview.agent.mapper.AgentModelConfigMapper;
import com.codereview.agent.mapper.AgentModelValidationMapper;
import com.codereview.agent.mapper.AgentProjectMapper;
import com.codereview.agent.mapper.AgentPromptMapper;
import com.codereview.agent.mapper.AgentPromptVersionMapper;
import com.codereview.agent.mapper.AgentReportMapper;
import com.codereview.agent.mapper.AgentReportSourceMapper;
import com.codereview.agent.mapper.AgentReviewResultMapper;
import com.codereview.agent.mapper.AgentReviewStrategyMapper;
import com.codereview.agent.mapper.AgentReviewTaskMapper;
import com.codereview.agent.mapper.AgentRunMapper;
import com.codereview.agent.mapper.AgentToolCallMapper;
import com.codereview.agent.mapper.AgentTaskQuotaMapper;
import com.codereview.agent.mapper.AgentTaskTargetMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 新库组件的装配入口。
 *
 * <p>只有显式配置 {@code agent.datasource.url} 才创建；未配置时旧功能照常启动，
 * v2 接口不注册。不把新库做成 {@code DataSource} Bean，避免影响旧库的自动配置。
 */
@Configuration
@EnableConfigurationProperties(AgentDataSourceProperties.class)
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "agent.datasource", name = "url")
public class AgentDataSourceConfig {

    static final List<Class<?>> MAPPERS = List.of(
            AgentProjectMapper.class,
            AgentModelConfigMapper.class,
            AgentModelValidationMapper.class,
            AgentPromptMapper.class,
            AgentPromptVersionMapper.class,
            AgentReviewStrategyMapper.class,
            AgentReviewTaskMapper.class,
            AgentTaskTargetMapper.class,
            AgentTaskQuotaMapper.class,
            AgentExecutionUnitMapper.class,
            AgentModelCallMapper.class,
            AgentRunMapper.class,
            AgentToolCallMapper.class,
            AgentMaterialMapper.class,
            AgentCallMaterialMapper.class,
            AgentReviewResultMapper.class,
            AgentIssueMapper.class,
            AgentFeedbackMapper.class,
            AgentReportMapper.class,
            AgentReportSourceMapper.class);

    @Bean(destroyMethod = "destroy")
    public AgentDatabase agentDatabase(AgentDataSourceProperties properties) {
        return new AgentDatabase(properties, MAPPERS);
    }
}
