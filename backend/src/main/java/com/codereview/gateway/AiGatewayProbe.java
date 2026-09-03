package com.codereview.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * AI 网关技术实验探针（M0 任务 4 之「AI 网关连通 + 稳定返回结构化 JSON」）。
 * <p>
 * 直接调用 DeepSeek 的 OpenAI 兼容接口 {@code https://api.deepseek.com/chat/completions}，
 * 通过 {@code response_format=json_object} 强制结构化输出，验证：
 * <ol>
 *   <li>网关连通性（HTTP 200 + finish_reason=stop）；</li>
 *   <li>能否稳定返回可解析、可校验的结构化 JSON。</li>
 * </ol>
 * 生产实现将改用 Spring RestClient/WebClient（见技术选型），本探针用 JDK HttpClient 保持最小依赖。
 */
public final class AiGatewayProbe {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 网关配置：base-url / api-key / model，均可被系统属性覆盖。 */
    public record Config(String baseUrl, String apiKey, String model) {
    }

    /** 一次对话结果。 */
    public record ChatResult(String model, String finishReason, String content,
                             int promptTokens, int completionTokens) {
    }

    /** 默认配置：密钥优先取系统属性 deepseek.api-key，其次取环境变量 DEEPSEEK_API_KEY。 */
    public static Config defaultConfig() {
        String baseUrl = System.getProperty("deepseek.base-url", "https://api.deepseek.com");
        String apiKey = System.getProperty("deepseek.api-key", System.getenv("DEEPSEEK_API_KEY"));
        String model = System.getProperty("deepseek.model", "deepseek-chat");
        return new Config(baseUrl, apiKey, model);
    }

    /** 发起一次 chat/completions 调用并返回内容。 */
    public ChatResult chat(Config cfg, String systemPrompt, String userPrompt) throws Exception {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", cfg.model());
        body.put("temperature", 0);
        body.putObject("response_format").put("type", "json_object");
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", systemPrompt);
        messages.addObject().put("role", "user").put("content", userPrompt);

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(cfg.baseUrl() + "/chat/completions"))
                .timeout(Duration.ofSeconds(120))
                .header("Authorization", "Bearer " + cfg.apiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                .build();

        HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + resp.statusCode() + ": " + resp.body());
        }
        JsonNode root = MAPPER.readTree(resp.body());
        JsonNode choice = root.path("choices").get(0);
        JsonNode usage = root.path("usage");
        return new ChatResult(
                root.path("model").asText(),
                choice.path("finish_reason").asText(),
                choice.path("message").path("content").asText(),
                usage.path("prompt_tokens").asInt(),
                usage.path("completion_tokens").asInt());
    }

    /** CLI：java -cp ... AiGatewayProbe */
    public static void main(String[] args) throws Exception {
        Config cfg = defaultConfig();
        if (cfg.apiKey() == null || cfg.apiKey().isBlank()) {
            System.err.println("未配置 DEEPSEEK_API_KEY（环境变量或 -Ddeepseek.api-key）");
            System.exit(2);
        }
        AiGatewayProbe probe = new AiGatewayProbe();
        ChatResult r = probe.chat(cfg,
                "你是代码审查助手，只输出合法 JSON，不要输出任何其他文字。",
                "请以 JSON 返回一段代码审查结果，字段包含 severity、category、title、suggestion。");
        System.out.println("model=" + r.model() + " finish_reason=" + r.finishReason()
                + " tokens=" + r.promptTokens() + "/" + r.completionTokens());
        System.out.println("content=" + r.content());
        MAPPER.readTree(r.content());
        System.out.println("JSON 校验: 通过");
    }
}
