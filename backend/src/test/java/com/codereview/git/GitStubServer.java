package com.codereview.git;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * git 客户端的本地 HTTP 桩（测试专用）。
 *
 * <p>用 JDK 自带的 {@code com.sun.net.httpserver.HttpServer}，**不引新依赖** ——
 * 与 {@code LlmClientTimeoutTest} 是同一套路。存在的意义是：`GitHubClient` 长期零覆盖，
 * "URL 拼对了吗、认证头对不对"只能靠人读源码；有了它就能把"实际发出的请求"变成断言。
 *
 * <p>记录的是 {@code exchange.getRequestURI()}（**原始未解码**形式），
 * 所以 `%2F` 与 `%252F` 的差别在这里看得一清二楚 —— 这正是发现"二次编码"的手段。
 */
class GitStubServer {

    private HttpServer server;
    private final List<String> requestUris = new ArrayList<>();
    private final List<String> authHeaders = new ArrayList<>();
    private Function<HttpExchange, String> responder;

    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestUris.add(exchange.getRequestURI().toString());
            authHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
            String body = responder == null ? "{}" : responder.apply(exchange);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** 固定返回同一个 JSON。 */
    void respondWith(String body) {
        this.responder = exchange -> body;
    }

    /** 按请求路径决定返回什么（用于验证"回退"这类分支）。 */
    void respondByPath(Function<HttpExchange, String> responder) {
        this.responder = responder;
    }

    String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    List<String> requestUris() {
        return requestUris;
    }

    List<String> authHeaders() {
        return authHeaders;
    }

    String uri(int index) {
        return requestUris.get(index);
    }

    /** 指向本桩的 git 配置：API 走根路径，raw 走 `/raw` 前缀，便于断言回退。 */
    GitProperties gitProperties() {
        GitProperties props = new GitProperties();
        props.setApiBase(base());
        props.setRawBase(base() + "/raw");
        return props;
    }

    GitHubClient newClient() {
        return new GitHubClient(new GitHubProperties(), gitProperties(), new GitCache(16, 16, 30));
    }
}
