package com.codereview.analyzer;

import com.codereview.review.ReviewStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * api-review 的失败语义（backlog ⑥「api-review 静默成功」）。
 *
 * <p>原先 {@code analyze} 永远返回 SUCCESS：触发失败只写进 result 的 {@code triggerError}，
 * 取结果失败被 {@code catch (Exception ignored)} 吞掉 —— 现场表现是"没结果也不报错"。
 * 这里把三种配置状态分别钉死：
 * <ul>
 *   <li>配了触发接口却失败 → 抛异常（由编排层落到 FAILED + error_message）；</li>
 *   <li>触发成功但结果拿不到 → PARTIAL（流水线跑了、issue 为空，可重审）；</li>
 *   <li>什么都没配（纯展示 resultUrl 的占位用法）→ 仍 SUCCESS，但 summary 如实写"未配置触发接口"。</li>
 * </ul>
 * 用本地 JDK HttpServer 打真实的 HTTP 失败路径（与 git 层的桩测试同一套路）。
 */
class ApiReviewAnalyzerTest {

    private HttpServer server;
    private String base;
    private final ApiReviewAnalyzer analyzer = new ApiReviewAnalyzer();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private void respond(String path, int status, String body) {
        server.createContext(path, exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
    }

    private AnalysisContext context(String apiUrl, String queryUrl) {
        ObjectNode params = new ObjectMapper().createObjectNode();
        params.put("apiUrl", apiUrl);
        params.put("resultUrl", "http://sonar.local/dashboard");
        params.put("queryUrl", queryUrl);
        return new AnalysisContext(null, null, null, List.of(), null, null, null, params, null, false, p -> {
        });
    }

    @Test
    void triggerFailureIsNotSilentlySuccessful() {
        respond("/trigger", 500, "{}");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> analyzer.analyze(context(base + "/trigger", "")));

        assertTrue(e.getMessage().contains("触发失败"), e.getMessage());
    }

    @Test
    void queryFailureBecomesPartialInsteadOfSuccess() {
        respond("/trigger", 200, "{}");
        respond("/query", 500, "{}");

        AnalyzeOutcome outcome = analyzer.analyze(context(base + "/trigger", base + "/query"));

        assertEquals(ReviewStatus.PARTIAL, outcome.status(),
                "流水线已触发、结果却拿不到：不能报 SUCCESS，否则这条记录会被当成有效结果");
        assertEquals(0, outcome.result().path("issues").size());
        assertFalse(outcome.result().path("queryError").asText().isEmpty(), "失败原因要留在结果里");
        assertTrue(outcome.result().path("summary").asText().contains("结果获取失败"),
                outcome.result().path("summary").asText());
    }

    @Test
    void querySuccessReturnsIssuesAndSuccess() {
        respond("/trigger", 200, "{}");
        respond("/query", 200, "{\"issues\":[{\"rule\":\"java:S1234\"}]}");

        AnalyzeOutcome outcome = analyzer.analyze(context(base + "/trigger", base + "/query"));

        assertEquals(ReviewStatus.SUCCESS, outcome.status());
        assertEquals(1, outcome.result().path("issues").size());
        assertTrue(outcome.result().path("triggered").asBoolean());
        assertTrue(outcome.result().path("queryError").isMissingNode());
    }

    @Test
    void placeholderWithoutAnyConfiguredUrlStaysSuccessButSaysSo() {
        AnalyzeOutcome outcome = analyzer.analyze(context("", ""));

        assertEquals(ReviewStatus.SUCCESS, outcome.status(), "什么都没配是合法的占位用法，不该判失败");
        assertFalse(outcome.result().path("triggered").asBoolean());
        assertTrue(outcome.result().path("summary").asText().contains("未配置触发接口"),
                "不能说成「触发失败」——根本没尝试过：" + outcome.result().path("summary").asText());
    }

    @Test
    void queryNotConfiguredKeepsSuccessAndExplainsWhy() {
        respond("/trigger", 200, "{}");

        AnalyzeOutcome outcome = analyzer.analyze(context(base + "/trigger", ""));

        assertEquals(ReviewStatus.SUCCESS, outcome.status());
        assertTrue(outcome.result().path("summary").asText().contains("未配置查询接口"),
                outcome.result().path("summary").asText());
    }
}
