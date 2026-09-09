package com.codereview.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * OpenAI 兼容 LLM 客户端（DeepSeek），json_object 模式 + temperature 0。
 * 由 M0 实验探针 AiGatewayProbe 演化而来，HTTP 客户端按技术选型改用 Spring RestClient。
 * M2 起支持按「模型配置（base_url/token/model）」参数化调用，yml 仅作默认模型 seed 源。
 */
@Component
public class LlmClient {

    private final LlmProperties props;
    private final ObjectMapper mapper = new ObjectMapper();
    private final RestClient restClient;

    public LlmClient(LlmProperties props) {
        this.props = props;
        this.restClient = RestClient.create();
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
}
