package com.codereview.agent.execution;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 仅在启用新库时开启期限扫描，旧功能不受影响。 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "agent.datasource", name = "url")
public class AgentTaskConfig {
}
