package com.codereview.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 审查并行执行器：reviewTaskExecutor 承载记录级 @Async 任务；reviewUnitExecutor 承载单元级并行（llm-review）。
 */
@Configuration
public class AsyncConfig {

    @Bean(name = "reviewTaskExecutor")
    public ThreadPoolTaskExecutor reviewTaskExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(2);
        ex.setMaxPoolSize(4);
        ex.setQueueCapacity(100);
        ex.setThreadNamePrefix("review-task-");
        ex.setWaitForTasksToCompleteOnShutdown(true);
        ex.initialize();
        return ex;
    }

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
