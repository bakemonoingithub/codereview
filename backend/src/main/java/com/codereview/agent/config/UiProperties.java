package com.codereview.agent.config;
import lombok.Data;
import org.springframework.stereotype.Component;
import org.springframework.boot.context.properties.ConfigurationProperties;
@Data
@Component
@ConfigurationProperties(prefix = "app.ui")
public class UiProperties {
    private String title = "智能代码分析工具";
    private String apiBase = "api/v2";
    private int requestTimeoutMillis = 30000;
    private int agentPollMillis = 2000;
    private int reportPollMillis = 2000;
    private int specialistPollMillis = 3000;
}
