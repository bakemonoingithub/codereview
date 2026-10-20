package com.codereview.agent.invocation;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本地 HTTP 替身验证真实传输边界：路径、鉴权头、正文、429 Retry-After 与超时分类。
 * 不连接真实模型；真实网关留 P30.8 清单与 P32.6 验收。
 */
class HttpModelTransportTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void postsTheExactBodyToChatCompletionsWithBearerCredential() throws Exception {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        start(exchange -> {
            path.set(exchange.getRequestURI().getPath());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, "{\"choices\":[]}", null);
        });
        HttpModelTransport transport = new HttpModelTransport(2);

        ModelTransport.TransportResponse response = transport.post(url("/v1/chat/completions"), "secret",
                "{\"model\":\"deepseek-chat\"}", Duration.ofSeconds(2));

        assertEquals(200, response.status());
        assertEquals("/v1/chat/completions", path.get());
        assertEquals("Bearer secret", authorization.get());
        assertEquals("{\"model\":\"deepseek-chat\"}", body.get());
        assertTrue(response.latencyMillis() >= 0);
    }

    @Test
    void keepsRetryAfterOnRateLimitResponses() throws Exception {
        start(exchange -> respond(exchange, 429, "{\"error\":\"rate\"}", "2"));
        HttpModelTransport transport = new HttpModelTransport(2);

        ModelTransport.TransportResponse response = transport.post(url("/v1"), null,
                "{}", Duration.ofSeconds(2));

        assertEquals(429, response.status());
        assertEquals("2", response.headers().get("retry-after"));
    }

    @Test
    void timeoutIsClassifiedInsteadOfReturningAnEmptySuccess() throws Exception {
        start(exchange -> {
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, "{}", null);
        });
        HttpModelTransport transport = new HttpModelTransport(2);

        ModelTransport.TransportFailure failure = assertThrows(ModelTransport.TransportFailure.class,
                () -> transport.post(url("/v1"), null, "{}", Duration.ofMillis(50)));

        assertTrue(failure.timeout());
    }

    @Test
    void invalidEndpointIsNotReportedAsATimeout() {
        HttpModelTransport transport = new HttpModelTransport(2);

        ModelTransport.TransportFailure failure = assertThrows(ModelTransport.TransportFailure.class,
                () -> transport.post("not-a-url", null, "{}", Duration.ofSeconds(1)));

        assertTrue(!failure.timeout());
    }

    private void start(Handler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                handler.handle(exchange);
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status,
                                String body, String retryAfter) throws IOException {
        if (retryAfter != null) {
            exchange.getResponseHeaders().add("Retry-After", retryAfter);
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @FunctionalInterface
    private interface Handler {
        void handle(com.sun.net.httpserver.HttpExchange exchange) throws IOException;
    }
}
