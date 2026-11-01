package com.codereview.agent.support;

import com.codereview.agent.entity.AgentModelConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** 三类入口的模型快照字段、顺序、null 与默认协议保持不变。 */
class ModelConfigSnapshotsTest {

    private static final String MODEL_ID = "01J00000000000000000000001";
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void specialistKeepsConnectionFieldsNullsAndRawProtocol() throws Exception {
        String json = mapper.writeValueAsString(ModelConfigSnapshots.specialist(model(null)));

        assertEquals("{\"id\":\"" + MODEL_ID + "\",\"name\":\"默认\","
                + "\"baseUrl\":\"https://model.local/v1\",\"modelName\":\"qwen\","
                + "\"contextWindow\":null,\"maxOutput\":4096,\"tokenizer\":null,"
                + "\"protocol\":null,\"revision\":3}", json);
        assertFalse(json.contains("credential"));
        assertFalse(json.contains("secret"));
    }

    @Test
    void reportDefaultsMissingProtocolAndKeepsTheSameFieldOrder() throws Exception {
        String json = mapper.writeValueAsString(ModelConfigSnapshots.report(model(null)));

        assertEquals("{\"id\":\"" + MODEL_ID + "\",\"name\":\"默认\","
                + "\"baseUrl\":\"https://model.local/v1\",\"modelName\":\"qwen\","
                + "\"contextWindow\":null,\"maxOutput\":4096,\"tokenizer\":null,"
                + "\"protocol\":\"OPENAI_CHAT\",\"revision\":3}", json);
        assertFalse(json.contains("secret"));
    }

    @Test
    void agentKeepsCompactFieldOrderWithoutConnectionFields() throws Exception {
        AgentModelConfig model = model("OPENAI_CHAT");
        model.setTokenizer("fixture-tokenizer");

        String json = mapper.writeValueAsString(ModelConfigSnapshots.agent(model));

        assertEquals("{\"id\":\"" + MODEL_ID + "\",\"name\":\"默认\",\"modelName\":\"qwen\","
                + "\"protocol\":\"OPENAI_CHAT\",\"revision\":3,"
                + "\"contextWindow\":null,\"maxOutput\":4096}", json);
        assertFalse(json.contains("baseUrl"));
        assertFalse(json.contains("tokenizer"));
        assertFalse(json.contains("secret"));
    }

    private static AgentModelConfig model(String protocol) {
        AgentModelConfig model = new AgentModelConfig();
        model.setId(MODEL_ID);
        model.setName("默认");
        model.setBaseUrl("https://model.local/v1");
        model.setModelName("qwen");
        model.setCredential("secret");
        model.setContextWindow(null);
        model.setMaxOutput(4096);
        model.setProtocol(protocol);
        model.setRevision(3);
        return model;
    }
}
