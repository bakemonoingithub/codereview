package com.codereview;

import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.context.annotation.Import;
import com.codereview.config.WebMvcConfig;
import com.codereview.config.JacksonConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@EnableAsync
@SpringBootApplication(scanBasePackages = {"com.codereview.agent", "com.codereview.git"},
        exclude = {DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class})
@Import({WebMvcConfig.class, JacksonConfig.class})
public class CodeReviewApplication {

    public static void main(String[] args) {
        if (java.util.Arrays.asList(args).contains("--check-config")) {
            checkConfiguration(args);
            return;
        }
        SpringApplication.run(CodeReviewApplication.class, args);
    }

    /** Same ConfigData/override rules as startup, without database or application components. */
    public static void checkConfiguration(String[] args) {
        SpringApplication check = new SpringApplication(
                com.codereview.agent.config.RuntimeConfigurationValidation.class);
        check.setWebApplicationType(org.springframework.boot.WebApplicationType.NONE);
        check.setRegisterShutdownHook(false);
        try (var context = check.run(args)) {
            if (context.getEnvironment().getProperty("agent.datasource.url", "").isBlank()) {
                throw new IllegalStateException("配置缺少 agent.datasource.url");
            }
            System.out.println("配置检查通过（未连接数据库、仓库或模型；连接可用性另行验证）");
        }
    }
}
