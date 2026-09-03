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

    /** 发起一次 chat/completions 调用，返回内容字符串（json_object 模式下为 JSON）。 */
    public String chatJson(String systemPrompt, String userPrompt) {
        if (props.getApiKey() == null || props.getApiKey().isBlank()) {
            throw new IllegalStateException("未配置 deepseek.api-key（环境变量 DEEPSEEK_API_KEY）");
        }
        ObjectNode body = mapper.createObjectNode();
        body.put("model", props.getModel());
        body.put("temperature", 0);
        body.putObject("response_format").put("type", "json_object");
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", systemPrompt);
        messages.addObject().put("role", "user").put("content", userPrompt);

        String resp = restClient.post()
                .uri(props.getBaseUrl() + "/chat/completions")
                .header("Authorization", "Bearer " + props.getApiKey())
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
}
