package com.codereview.analyzer;

import com.codereview.review.ReviewStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * api-review 分析器（占位实现）：
 * 触发时异步 POST「调用 API」启动流水线（不阻塞），结果以「结果展示地址」URL 标签呈现；
 * 若配置了「查询具体结果地址」，尝试 GET SonarQube Web API 并透传 issues（token 未就绪时保留空）。
 */
@Component
public class ApiReviewAnalyzer implements Analyzer {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestClient restClient = restClient();

    @Override
    public int type() {
        return 4;
    }

    @Override
    public AnalyzeOutcome analyze(AnalysisContext ctx) {
        JsonNode params = ctx.params() == null ? objectMapper.createObjectNode() : ctx.params();
        String apiUrl = params.path("apiUrl").asText("");
        String resultUrl = params.path("resultUrl").asText("");
        String queryUrl = params.path("queryUrl").asText("");
        String token = params.path("token").asText("");

        ObjectNode result = objectMapper.createObjectNode();
        result.put("type", "api-review");
        result.put("resultUrl", resultUrl);

        boolean triggered = false;
        String triggerError = null;
        if (!apiUrl.isBlank()) {
            try {
                post(apiUrl, token);
                triggered = true;
            } catch (Exception e) {
                triggerError = e.getMessage();
            }
        }
        result.put("triggered", triggered);
        if (triggerError != null) {
            result.put("triggerError", triggerError);
        }

        ArrayNode issues = objectMapper.createArrayNode();
        if (!queryUrl.isBlank()) {
            try {
                issues = normalize(fetch(queryUrl, token));
            } catch (Exception ignored) {
                // SonarQube 未就绪/接口失败时保留空结果
            }
        }
        result.set("issues", issues);
        result.put("summary", String.format("api-review：触发%s，结果见 %s%s",
                triggered ? "成功" : "失败", resultUrl,
                queryUrl.isBlank() ? "（未配置查询接口）" : ""));
        return new AnalyzeOutcome(result, ReviewStatus.SUCCESS);
    }

    private void post(String url, String token) {
        if (token != null && !token.isBlank()) {
            restClient.post().uri(url).contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .body("{}").retrieve().body(String.class);
            return;
        }
        restClient.post().uri(url).contentType(MediaType.APPLICATION_JSON)
                .body("{}").retrieve().body(String.class);
    }

    private String fetch(String url, String token) {
        if (token != null && !token.isBlank()) {
            return restClient.get().uri(url)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .retrieve().body(String.class);
        }
        return restClient.get().uri(url).retrieve().body(String.class);
    }

    private ArrayNode normalize(String json) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        JsonNode arr = root.path("issues");
        if (arr.isArray()) {
            return (ArrayNode) arr;
        }
        return objectMapper.createArrayNode();
    }

    private static RestClient restClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(20_000);
        return RestClient.builder().requestFactory(factory).build();
    }
}
