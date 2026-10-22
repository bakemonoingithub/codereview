package com.codereview.agent.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpModelProbeTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private HttpModelProbe probeWith(int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> respond(exchange, status, body));
        server.start();
        return new HttpModelProbe(new ObjectMapper(), 3);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    void acceptsAJsonObjectWithNonEmptyChoices() throws IOException {
        HttpModelProbe probe = probeWith(200, "{\"choices\":[{\"message\":{\"content\":\"pong\"}}]}");

        assertTrue(probe.probe(new ModelProbeRequest(baseUrl(), "model", "key")).passed());
    }

    @Test
    void rejectsPlainTextThatMerelyMentionsChoices() throws IOException {
        HttpModelProbe probe = probeWith(200, "this mentions \"choices\" but is not json");

        ModelProbeOutcome outcome = probe.probe(new ModelProbeRequest(baseUrl(), "model", "key"));

        assertFalse(outcome.passed());
        assertTrue(outcome.detail().contains("JSON"));
    }

    @Test
    void rejectsEmptyChoicesArray() throws IOException {
        HttpModelProbe probe = probeWith(200, "{\"choices\":[]}");

        assertFalse(probe.probe(new ModelProbeRequest(baseUrl(), "model", "key")).passed());
    }

    @Test
    void rejectsNonSuccessfulStatus() throws IOException {
        HttpModelProbe probe = probeWith(500, "{\"choices\":[{\"message\":{}}]}");

        assertFalse(probe.probe(new ModelProbeRequest(baseUrl(), "model", "key")).passed());
    }
}
