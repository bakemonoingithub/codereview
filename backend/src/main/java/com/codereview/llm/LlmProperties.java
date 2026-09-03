package com.codereview.llm;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * DeepSeek 端点配置（OpenAI 兼容）；开发期走公网 api.deepseek.com，部署期切内网 AI 网关
 */
@Data
@Component
@ConfigurationProperties(prefix = "deepseek")
public class LlmProperties {
    private String baseUrl = "https://api.deepseek.com";
    private String apiKey = "";
    private String model = "deepseek-chat";
}
