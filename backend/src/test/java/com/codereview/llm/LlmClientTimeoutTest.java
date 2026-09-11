package com.codereview.llm;

import com.codereview.review.RetryPolicy;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LLM 客户端超时（A 批 E2）。
 *
 * <p>回归背景：本客户端原先用裸 {@code RestClient.create()}，是全仓 4 个 HTTP 客户端里
 * **唯一没设超时**的一个。网关卡住时单元线程永久阻塞，配合
 * {@code LlmReviewAnalyzer} 的 {@code while (done < submitted) cs.take()} 会无限等待 ——
 * 记录永远停在"执行中"，并占满并发线程拖垮后续审查。
 *
 * <p>这里用本地"只会挂着不回应"的 HTTP 服务复现网关卡死，断言客户端会**超时返回**而不是一直等。
 */
class LlmClientTimeoutTest {

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void chatAbortsOnReadTimeoutInsteadOfHangingForever() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        HttpServer server = hangingServer(release);
        try {
            LlmProperties props = new LlmProperties();
            props.setConnectTimeoutMs(500);
            props.setReadTimeoutMs(300);
            LlmClient client = new LlmClient(props);

            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            long startedAt = System.nanoTime();

            Throwable thrown = assertThrows(Exception.class,
                    () -> client.chat(baseUrl, "sk-test", "test-model", "sys", "user"));
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

            // 超时**不是** ResourceAccessException：读响应阶段超时被 Spring 包成
            // RestClientException，SocketTimeoutException 在 cause 链里（实测确认）。
            assertTrue(hasCause(thrown, SocketTimeoutException.class),
                    "超时的 cause 链里应有 SocketTimeoutException；实际: " + thrown);
            // 关键：这条异常必须被判为可重试，否则"网关变慢"会被当成永久失败
            assertTrue(RetryPolicy.isRetryable(thrown),
                    "读超时应可重试，否则加超时反而剥夺了重试机会；实际: " + thrown);
            assertTrue(elapsedMs < 10_000,
                    "应在读超时附近返回；上游会挂 30 秒，实际耗时 " + elapsedMs + "ms");
        } finally {
            release.countDown();
            server.stop(0);
        }
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void chatJsonAbortsOnReadTimeoutToo() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        HttpServer server = hangingServer(release);
        try {
            LlmProperties props = new LlmProperties();
            props.setConnectTimeoutMs(500);
            props.setReadTimeoutMs(300);
            LlmClient client = new LlmClient(props);

            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            Throwable thrown = assertThrows(Exception.class,
                    () -> client.chatJson(baseUrl, "sk-test", "test-model", "sys", "user"));

            assertTrue(hasCause(thrown, SocketTimeoutException.class), "实际: " + thrown);
            assertTrue(RetryPolicy.isRetryable(thrown), "实际: " + thrown);
        } finally {
            release.countDown();
            server.stop(0);
        }
    }

    /** 沿 cause 链查找指定类型 */
    private static boolean hasCause(Throwable e, Class<? extends Throwable> type) {
        for (Throwable current = e; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return true;
            }
            if (current.getCause() == current) {
                return false;
            }
        }
        return false;
    }

    @Test
    void nonPositiveTimeoutFallsBackToDefault() {
        // 0/负数在 HttpURLConnection 里是"永不超时"，必须回退，否则配置写 0 会静默放回挂死风险
        assertEquals(500, LlmClient.timeoutOrDefault(500, LlmClient.DEFAULT_READ_TIMEOUT_MS));
        assertEquals(LlmClient.DEFAULT_READ_TIMEOUT_MS,
                LlmClient.timeoutOrDefault(0, LlmClient.DEFAULT_READ_TIMEOUT_MS));
        assertEquals(LlmClient.DEFAULT_READ_TIMEOUT_MS,
                LlmClient.timeoutOrDefault(-1, LlmClient.DEFAULT_READ_TIMEOUT_MS));
    }

    @Test
    void propertiesShipBoundedDefaults() {
        LlmProperties props = new LlmProperties();
        assertTrue(props.getConnectTimeoutMs() > 0, "建连超时应默认有限");
        assertTrue(props.getReadTimeoutMs() > 0, "读超时应默认有限");
    }

    /** 一个只会挂着不回应（模拟网关卡死）的本地 HTTP 服务 */
    private static HttpServer hangingServer(CountDownLatch release) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            try {
                exchange.sendResponseHeaders(200, -1);
            } catch (IOException ignored) {
                // 客户端已超时断开，写响应必然失败，属预期
            } finally {
                exchange.close();
            }
        });
        // 守护线程：避免测试结束时有非守护 handler 线程拖住 JVM
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        }));
        server.start();
        return server;
    }
}
