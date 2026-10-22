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
        SpringApplication.run(CodeReviewApplication.class, args);
    }
}
