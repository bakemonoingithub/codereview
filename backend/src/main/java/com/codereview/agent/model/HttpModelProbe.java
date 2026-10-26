package com.codereview.agent.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
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

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final Duration readTimeout;

    @org.springframework.beans.factory.annotation.Autowired
    public HttpModelProbe(ObjectMapper mapper, com.codereview.agent.invocation.ModelInvocationProperties properties) {
        this(mapper, properties.getProbeTimeoutSeconds(), properties.getProbeConnectTimeoutSeconds());
    }
    public HttpModelProbe(ObjectMapper mapper, long timeoutSeconds) { this(mapper, timeoutSeconds, 5); }
    private HttpModelProbe(ObjectMapper mapper, long timeoutSeconds, long connectSeconds) {
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(connectSeconds)).build();
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
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return new ModelProbeOutcome(false, "网关返回 HTTP " + response.statusCode() + "，未取得预期 JSON");
            }
            return checkJson(response.body());
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

    /**
     * 必须真正解析 JSON 并检查最低结构：普通文本里出现 "choices" 不能被当成通过。
     *
     * <p>只校验对象且 {@code choices} 为非空数组；不评价候选内容质量，也不证明
     * 完整上下文窗口或工具调用能力。
     */
    private ModelProbeOutcome checkJson(String body) {
        if (body == null || body.isBlank()) {
            return new ModelProbeOutcome(false, "网关返回空响应");
        }
        try {
            JsonNode root = mapper.readTree(body);
            if (root.isObject() && root.path("choices").isArray() && !root.path("choices").isEmpty()) {
                return new ModelProbeOutcome(true, "连通成功，返回含 choices 的 JSON");
            }
            return new ModelProbeOutcome(false, "返回 JSON 缺少非空 choices，未取得预期结构");
        } catch (JsonProcessingException e) {
            return new ModelProbeOutcome(false, "返回内容不是合法 JSON");
        }
    }
}
