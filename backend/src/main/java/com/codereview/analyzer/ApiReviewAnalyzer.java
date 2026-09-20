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
        String queryError = null;
        if (!queryUrl.isBlank()) {
            try {
                issues = normalize(fetch(queryUrl, token));
            } catch (Exception e) {
                // 不再静默：取结果失败必须体现在状态上（backlog ⑥「api-review 静默成功」）
                queryError = e.getMessage();
            }
        }
        result.set("issues", issues);
        if (queryError != null) {
            result.put("queryError", queryError);
        }

        // 配了触发接口却触发失败：抛异常 → ReviewExecutor 统一落到 FAILED + error_message
        // （与 ④ 之后"失败原因统一走 error_message"的口径一致）
        if (!apiUrl.isBlank() && !triggered) {
            throw new IllegalStateException("api-review 触发失败："
                    + (triggerError == null ? "未知错误" : triggerError));
        }

        StringBuilder summary = new StringBuilder("api-review：");
        if (apiUrl.isBlank()) {
            summary.append("未配置触发接口");
        } else {
            summary.append("触发成功");
        }
        summary.append("，结果见 ").append(resultUrl);
        if (queryUrl.isBlank()) {
            summary.append("（未配置查询接口）");
        }
        if (queryError != null) {
            summary.append("；结果获取失败：").append(queryError);
        }
        result.put("summary", summary.toString());

        // 触发成功但结果拿不到：流水线确实跑了、issue 却为空 —— 记 PARTIAL（可重审），
        // 而不是 SUCCESS（原先"没结果也不报错"会让这条记录被当成有效结果参与准确率统计）
        int status = queryError == null ? ReviewStatus.SUCCESS : ReviewStatus.PARTIAL;
        return new AnalyzeOutcome(result, status);
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
