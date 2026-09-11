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
    /** 建连超时（毫秒） */
    private long connectTimeoutMs = 10_000;
    /**
     * 读超时（毫秒）。**必须有限**：没有它，网关卡住会让单元线程永久阻塞，
     * 记录永远停在"执行中"并占满并发线程。见 {@link LlmClient}。
     */
    private long readTimeoutMs = 300_000;
}
