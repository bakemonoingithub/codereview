package com.codereview.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * AI 网关实验断言：验证连通性 + 稳定返回结构化 JSON。
 * 密钥从环境变量 DEEPSEEK_API_KEY 读取；未配置时跳过（不失败）。
 */
class AiGatewayProbeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void connectivity() throws Exception {
        AiGatewayProbe.Config cfg = AiGatewayProbe.defaultConfig();
        assumeTrue(cfg.apiKey() != null && !cfg.apiKey().isBlank(), "跳过：未配置 DEEPSEEK_API_KEY");

        AiGatewayProbe.ChatResult r = new AiGatewayProbe().chat(cfg,
                "你是代码审查助手，只输出合法 JSON，不要输出任何其他文字。",
                "请以 JSON 返回一句话确认，字段为 {\"ok\": true}");

        assertEquals("stop", r.finishReason(), "finish_reason 应为 stop");
        assertNotNull(r.content());
        assertFalse(r.content().isBlank());
        JsonNode node = MAPPER.readTree(r.content()); // 必须能被解析为合法 JSON
        assertTrue(node.has("ok"), "返回 JSON 应包含 ok 字段");
        System.out.println("connectivity OK | model=" + r.model()
                + " | tokens=" + r.promptTokens() + "/" + r.completionTokens()
                + " | content=" + r.content());
    }

    @Test
    void structuredJsonReview() throws Exception {
        AiGatewayProbe.Config cfg = AiGatewayProbe.defaultConfig();
        assumeTrue(cfg.apiKey() != null && !cfg.apiKey().isBlank(), "跳过：未配置 DEEPSEEK_API_KEY");

        String code = """
                public class OrderService {
                    private OrderRepository repo;
                    public void doOrder(Long id) {
                        Order o = repo.find(id);
                        if (o == null) return;
                        o.setStatus(1);
                    }
                }
                """;
        String schema = """
                请以 JSON 返回代码审查结果，格式固定为：
                {"issues":[{"severity":"error|warning|info","category":"...","line":数字,"title":"...","suggestion":"..."}],"summary":"..."}
                只输出这一个 JSON 对象，不要输出任何其他文字。
                """;

        // 稳定性质控：连续 3 次都必须返回可解析且符合 schema 的 JSON
        for (int i = 1; i <= 3; i++) {
            AiGatewayProbe.ChatResult r = new AiGatewayProbe().chat(cfg,
                    "你是代码审查助手，只输出合法 JSON，不要输出任何其他文字。",
                    code + "\n" + schema);

            JsonNode root = MAPPER.readTree(r.content()); // 合法 JSON
            assertTrue(root.has("issues"), "第 " + i + " 次：应包含 issues 数组");
            assertTrue(root.get("issues").isArray(), "第 " + i + " 次：issues 应为数组");
            assertTrue(root.has("summary"), "第 " + i + " 次：应包含 summary");
            for (JsonNode issue : root.get("issues")) {
                assertTrue(issue.has("severity"), "第 " + i + " 次：issue 缺 severity");
                assertTrue(issue.has("title"), "第 " + i + " 次：issue 缺 title");
                assertTrue(issue.has("suggestion"), "第 " + i + " 次：issue 缺 suggestion");
            }
            System.out.println("structuredJson OK 第 " + i + " 次 | issues=" + root.get("issues").size()
                    + " | summary=" + root.get("summary").asText());
        }
    }
}
