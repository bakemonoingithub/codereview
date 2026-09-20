package com.codereview.git;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 *
 * <p>{@link StubResponse} 支持自定义状态码与响应头：Gitea 的分页完全依赖
 * {@code X-Total-Count} / {@code Link} 两个头，拿不到头就测不了翻页行为。
 */
class GitStubServer {

    /** 桩的一次响应：状态码 + 响应头 + 响应体。 */
    record StubResponse(int status, Map<String, String> headers, String body) {

        static StubResponse ok(String body) {
            return new StubResponse(200, Map.of(), body);
        }

        StubResponse withHeader(String name, String value) {
            Map<String, String> merged = new LinkedHashMap<>(headers);
            merged.put(name, value);
            return new StubResponse(status, merged, body);
        }
    }

    private HttpServer server;
    private final List<String> requestUris = new ArrayList<>();
    private final List<String> authHeaders = new ArrayList<>();
    private Function<HttpExchange, StubResponse> responder;

    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestUris.add(exchange.getRequestURI().toString());
            authHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
            StubResponse resp = responder == null ? StubResponse.ok("{}") : responder.apply(exchange);
            byte[] bytes = resp.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            resp.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
            exchange.sendResponseHeaders(resp.status(), bytes.length);
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
        this.responder = exchange -> StubResponse.ok(body);
    }

    /** 按请求路径决定返回什么（用于验证"回退"这类分支）。 */
    void respondByPath(Function<HttpExchange, String> responder) {
        this.responder = exchange -> StubResponse.ok(responder.apply(exchange));
    }

    /** 需要自定义状态码/响应头（分页头、错误体）时用这个。 */
    void respond(Function<HttpExchange, StubResponse> responder) {
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

    /** 测试用的仓库地址（GitHub 客户端不看 baseUrl，Gitea 客户端用它推导站点根）。 */
    static GitRepoRef repo(String owner, String name) {
        return new GitRepoRef("http://stub.invalid", "stub.invalid", owner, name);
    }
}
