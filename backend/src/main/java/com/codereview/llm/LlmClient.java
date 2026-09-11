package com.codereview.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * OpenAI 兼容 LLM 客户端（DeepSeek），json_object 模式 + temperature 0。
 * 由 M0 实验探针 AiGatewayProbe 演化而来，HTTP 客户端按技术选型改用 Spring RestClient。
 * M2 起支持按「模型配置（base_url/token/model）」参数化调用，yml 仅作默认模型 seed 源。
 */
@Component
public class LlmClient {

    /** 配置里给了非正值时的兜底建连超时（毫秒） */
    static final int DEFAULT_CONNECT_TIMEOUT_MS = 10_000;
    /** 配置里给了非正值时的兜底读超时（毫秒） */
    static final int DEFAULT_READ_TIMEOUT_MS = 300_000;

    private final LlmProperties props;
    private final ObjectMapper mapper = new ObjectMapper();
    private final RestClient restClient;

    public LlmClient(LlmProperties props) {
        this.props = props;
        this.restClient = buildRestClient(props);
    }

    /**
     * 建带超时的 RestClient。
     *
     * <p>这层超时是**防挂死**的关键：没有它，网关卡住会让单元线程永久阻塞，而
     * {@code LlmReviewAnalyzer} 的 {@code while (done < submitted) cs.take()} 会无限等待 ——
     * 记录永远停在"执行中"、进度不动，并占满并发线程（默认 4）导致后续审查全部排队。
     *
     * <p>超时抛 {@code ResourceAccessException}，恰好命中 {@code RetryPolicy.isRetryable}
     * 的"网络超时"分支 ⇒ 有限次退避重试后该单元失败，记录收敛到失败/部分成功，
     * 而不是无限等待。
     */
    private static RestClient buildRestClient(LlmProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutOrDefault(props.getConnectTimeoutMs(), DEFAULT_CONNECT_TIMEOUT_MS));
        factory.setReadTimeout(timeoutOrDefault(props.getReadTimeoutMs(), DEFAULT_READ_TIMEOUT_MS));
        return RestClient.builder().requestFactory(factory).build();
    }

    /**
     * 0/负数在 {@code HttpURLConnection} 语义里是"**永不超时**"，与本次修复目的正好相反，
     * 因此一律回退到默认值，避免配置写 0 时静默地把挂死风险放回来。
     */
    static int timeoutOrDefault(long value, int fallback) {
        return value > 0 ? (int) Math.min(value, Integer.MAX_VALUE) : fallback;
    }

    /** 按模型配置发起一次 chat/completions 调用，返回内容字符串（json_object 模式下为 JSON）。 */
    public String chatJson(String baseUrl, String apiKey, String model, String systemPrompt, String userPrompt) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("模型未配置 token（apiKey）");
        }
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("temperature", 0);
        body.putObject("response_format").put("type", "json_object");
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", systemPrompt);
        messages.addObject().put("role", "user").put("content", userPrompt);

        String resp = restClient.post()
                .uri(baseUrl + "/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);

        try {
            JsonNode root = mapper.readTree(resp);
            return root.path("choices").get(0).path("message").path("content").asText();
        } catch (Exception e) {
            throw new IllegalStateException("LLM 响应解析失败: " + e.getMessage(), e);
        }
    }

    /** 使用 yml 默认模型（deepseek）的便捷入口。 */
    public String chatJson(String systemPrompt, String userPrompt) {
        return chatJson(props.getBaseUrl(), props.getApiKey(), props.getModel(), systemPrompt, userPrompt);
    }

    /** 非 JSON 模式的普通对话，返回内容字符串（用于报告等 Markdown 输出）。 */
    public String chat(String baseUrl, String apiKey, String model, String systemPrompt, String userPrompt) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("模型未配置 token（apiKey）");
        }
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("temperature", 0);
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", systemPrompt);
        messages.addObject().put("role", "user").put("content", userPrompt);

        String resp = restClient.post()
                .uri(baseUrl + "/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);

        try {
            JsonNode root = mapper.readTree(resp);
            return root.path("choices").get(0).path("message").path("content").asText();
        } catch (Exception e) {
            throw new IllegalStateException("LLM 响应解析失败: " + e.getMessage(), e);
        }
    }

    /** 连通性验证：发一个极简 chat/completions 请求，能返回 200 即视为连通（否则抛异常）。 */
    public void ping(String baseUrl, String apiKey, String model) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("模型未配置 token（apiKey）");
        }
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", 1);
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "user").put("content", "ping");
        restClient.post()
                .uri(baseUrl + "/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
    }
}
