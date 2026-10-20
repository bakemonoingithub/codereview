package com.codereview.agent.invocation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * OpenAI 兼容的 HTTP 传输。
 *
 * <p>只做一次请求，返回状态、正文、头和耗时；超时与连接失败明确标记，不在这里重试，
 * 避免与调用层、业务层叠加放大额度。
 */
@Component
public class HttpModelTransport implements ModelTransport {

    private final HttpClient http;
    private final Duration connectTimeout;

    public HttpModelTransport(@Value("${agent.model.connect-timeout-seconds:10}") long connectSeconds) {
        this.connectTimeout = Duration.ofSeconds(Math.max(1, connectSeconds));
        this.http = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
    }

    @Override
    public TransportResponse post(String endpoint, String credential, String body, Duration timeout) {
        long start = System.nanoTime();
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            if (credential != null && !credential.isBlank()) {
                builder.header("Authorization", "Bearer " + credential);
            }
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            Map<String, String> headers = new LinkedHashMap<>();
            response.headers().map().forEach((name, values) ->
                    headers.put(name.toLowerCase(Locale.ROOT), values.isEmpty() ? "" : values.get(0)));
            return new TransportResponse(response.statusCode(), response.body(), headers,
                    Duration.ofNanos(System.nanoTime() - start).toMillis());
        } catch (HttpTimeoutException e) {
            throw new TransportFailure(true, "模型响应超时", e);
        } catch (IOException e) {
            throw new TransportFailure(false, "无法连接模型网关：" + e.getClass().getSimpleName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TransportFailure(false, "模型调用被中断", e);
        } catch (IllegalArgumentException e) {
            throw new TransportFailure(false, "模型接口地址无效", e);
        }
    }
}
