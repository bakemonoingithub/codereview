package com.codereview.agent.tools;

import com.codereview.agent.tools.capability.AgentToolCapabilities;
import com.codereview.agent.tools.capability.ToolCapability;
import com.codereview.agent.tools.capability.ToolParameter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * PAR-04 工具描述冻结基线。
 *
 * <p>期望值来自改造前实现（一次性捕获后删除捕获测试）：完整工具 JSON、序列化字节数与
 * schema 版本指纹。重构后不得重新捕获以消除差异；对象键顺序不影响 JSON 比较，
 * 但字节数必须一致，因为工具定义会随请求发送并计入容量估算。
 */
class ToolDefinitionBaselineTest {

    private static final Path BASELINE = Path.of("src/test/resources/tool-baseline/tools.json");

    @Test
    void definitionsStayEqualAndKeepTheirSerializedSize() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode expected = mapper.readTree(Files.readString(BASELINE));

        assertEquals(expected.get("definitions"), mapper.valueToTree(AgentToolSchema.definitions()),
                "只读工具定义必须与改造前逐字一致");
        assertEquals(expected.get("agentTools"), mapper.valueToTree(AgentToolSchema.agentTools()),
                "Agent 工具定义（含 submit_result）必须与改造前逐字一致");
        assertEquals(expected.get("definitionsBytes").asInt(), serializedBytes(mapper, AgentToolSchema.definitions()));
        assertEquals(expected.get("agentToolsBytes").asInt(), serializedBytes(mapper, AgentToolSchema.agentTools()));
        assertEquals(expected.get("versionDefault").asText(), AgentToolSchema.version());
    }

    @Test
    void frozenLimitsStillDriveDescriptionsAndVersionFingerprint() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode expected = mapper.readTree(Files.readString(BASELINE));
        ToolLimitProperties custom = customLimits();

        assertEquals(expected.get("definitionsCustomLimits"),
                mapper.valueToTree(AgentToolSchema.definitions(custom)),
                "自定义限额下的默认值与上限必须照旧写入描述");
        assertEquals(expected.get("versionCustomLimits").asText(), AgentToolSchema.version(custom));
    }

    @Test
    void adapterEmitsExactlyTheDeclaredCapabilities() {
        ToolLimitProperties limits = new ToolLimitProperties();
        List<Map<String, Object>> tools = AgentToolSchema.agentTools(limits);
        List<ToolCapability> capabilities = AgentToolCapabilities.agent(limits);

        assertEquals(capabilities.size(), tools.size());
        for (int index = 0; index < capabilities.size(); index++) {
            ToolCapability capability = capabilities.get(index);
            Map<?, ?> function = (Map<?, ?>) tools.get(index).get("function");
            assertEquals(capability.name(), function.get("name"));
            assertEquals(capability.description(), function.get("description"));
            Map<?, ?> parameters = (Map<?, ?>) function.get("parameters");
            Map<?, ?> properties = (Map<?, ?>) parameters.get("properties");
            assertEquals(capability.parameterNames(), new ArrayList<>(properties.keySet()),
                    "属性顺序必须与能力定义一致，字节数与计量才不漂移");
            assertEquals(capability.requiredParameters(), parameters.get("required"));
            assertEquals(false, parameters.get("additionalProperties"));
            for (ToolParameter parameter : capability.parameters()) {
                String schema = new ObjectMapper().valueToTree(properties.get(parameter.name())).toString();
                assertFalse(schema.contains("\"description\":null"), capability.name() + "." + parameter.name());
                assertNotNull(properties.get(parameter.name()));
            }
        }
    }

    private static int serializedBytes(ObjectMapper mapper, List<Map<String, Object>> tools) throws Exception {
        return mapper.writeValueAsString(tools).getBytes(StandardCharsets.UTF_8).length;
    }

    private static ToolLimitProperties customLimits() {
        ToolLimitProperties custom = new ToolLimitProperties();
        custom.setTreeDefaultDepth(1);
        custom.setTreeMaxDepth(4);
        custom.setTreeDefaultLimit(30);
        custom.setTreeMaxLimit(150);
        custom.setFindDefaultLimit(20);
        custom.setFindMaxLimit(70);
        custom.setSearchDefaultLimit(40);
        custom.setSearchMaxLimit(90);
        custom.setSearchContextLines(3);
        custom.setReadDefaultLines(120);
        custom.setReadMaxLines(300);
        custom.setDiffMaxItems(12);
        custom.setDiffMaxHunks(6);
        custom.setMaterialMaxBytes(24 * 1024 + 512);
        custom.setEnvelopeMaxBytes(40 * 1024);
        return custom;
    }
}
