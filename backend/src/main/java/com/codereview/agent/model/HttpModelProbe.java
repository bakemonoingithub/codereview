package com.codereview.agent.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 最小连通探针：POST {baseUrl}/chat/completions，8 token 的固定提问。
 *
 * <p>刻意不发送业务代码，也不把响应正文写进日志或验证记录 —— 它只回答
 * "这个地址和凭据现在能不能用"，不回答"模型是否可靠"。
 */
@Component
public class HttpModelProbe implements ModelProbe {

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper mapper;
    private final Duration readTimeout;

    public HttpModelProbe(ObjectMapper mapper,
                          @Value("${agent.model.probe-timeout-seconds:15}") long timeoutSeconds) {
        this.mapper = mapper;
        this.readTimeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
    }

    @Override
    public ModelProbeOutcome probe(ModelProbeRequest request) {
        if (request.baseUrl() == null || request.baseUrl().isBlank()) {
            return new ModelProbeOutcome(false, "接口地址为空");
        }
        String endpoint = trimTrailingSlash(request.baseUrl());
        if (!endpoint.endsWith("/chat/completions")) {
            endpoint = endpoint + "/chat/completions";
        }
        try {
            String body = mapper.writeValueAsString(Map.of(
                    "model", request.modelName(),
                    "messages", List.of(Map.of("role", "user", "content", "ping")),
                    "max_tokens", 8,
                    "stream", false));
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(readTimeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            if (request.credential() != null && !request.credential().isBlank()) {
                builder.header("Authorization", "Bearer " + request.credential());
            }
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300 && response.body() != null
                    && response.body().contains("\"choices\"")) {
                return new ModelProbeOutcome(true, "连通成功，返回 JSON 结果");
            }
            return new ModelProbeOutcome(false, "网关返回 HTTP " + response.statusCode() + "，未取得预期 JSON");
        } catch (IOException e) {
            return new ModelProbeOutcome(false, "无法连接网关：" + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ModelProbeOutcome(false, "验证被中断");
        } catch (IllegalArgumentException e) {
            return new ModelProbeOutcome(false, "接口地址无效");
        }
    }

    private static String trimTrailingSlash(String value) {
        String trimmed = value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
