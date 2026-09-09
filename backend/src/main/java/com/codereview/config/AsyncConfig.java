package com.codereview.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 单元级审查并行执行器（限流并发）。
 */
@Configuration
public class AsyncConfig {

    @Bean(name = "reviewUnitExecutor")
    public ThreadPoolTaskExecutor reviewUnitExecutor(ReviewProperties props) {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(props.getConcurrency());
        ex.setMaxPoolSize(props.getConcurrency());
        ex.setQueueCapacity(1000);
        ex.setThreadNamePrefix("review-unit-");
        ex.setWaitForTasksToCompleteOnShutdown(true);
        ex.initialize();
        return ex;
    }
}
