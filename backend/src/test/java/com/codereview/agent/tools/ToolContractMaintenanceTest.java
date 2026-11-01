package com.codereview.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 只读工具的第一档维护检查：冻结限额同时驱动模型说明与参数解析。
 *
 * <p>这里只核对默认值、范围、行数／条目上限这类机械约束，不要求把路径、查询、
 * 游标和完整性语义也生成成说明；那些仍由 {@link ToolArgumentParser}、各工具实现和
 * 维护索引关联。
 */
class ToolContractMaintenanceTest {

    private static final String SHA = "b7a312e0c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6";
    private static final String HEAD = "c1a312e0c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f7";

    private final ToolArgumentParser parser = new ToolArgumentParser(new ObjectMapper());

    @Test
    void customFrozenLimitsDriveBothDescriptionsAndParsing() {
        ToolLimitProperties limits = customLimits();
        AgentToolScope scope = new AgentToolScope("p1", Map.of("V1", SHA),
                AgentToolScope.comparisonsOf(new AgentToolScope.Comparison("C1", HEAD, SHA)), limits);

        assertEquals(1, parser.parse(AgentToolNames.TREE, "{\"version\":\"V1\"}", scope).depth());
        assertEquals(30, parser.parse(AgentToolNames.TREE, "{\"version\":\"V1\"}", scope).limit());
        assertEquals(20, parser.parse(AgentToolNames.FIND_FILES,
                "{\"version\":\"V1\",\"glob\":\"*.java\"}", scope).limit());
        assertEquals(40, parser.parse(AgentToolNames.SEARCH_CODE,
                "{\"version\":\"V1\",\"query\":\"needle\"}", scope).limit());
        assertEquals(300, parser.parse(AgentToolNames.READ_CODE,
                "{\"version\":\"V1\",\"path\":\"src/A.java\"}", scope).limit());
        assertEquals(6, parser.parse(AgentToolNames.READ_DIFF,
                "{\"comparisonId\":\"C1\"}", scope).limit());

        assertTrue(parameterDescription(limits, AgentToolNames.TREE, "depth").contains("1"));
        assertTrue(parameterDescription(limits, AgentToolNames.TREE, "limit").contains("30"));
        assertTrue(parameterDescription(limits, AgentToolNames.FIND_FILES, "limit").contains("20"));
        assertTrue(parameterDescription(limits, AgentToolNames.SEARCH_CODE, "limit").contains("40"));
        assertTrue(toolDescription(limits, AgentToolNames.READ_CODE).contains("300"));
        assertTrue(toolDescription(limits, AgentToolNames.READ_DIFF).contains("12"));
        assertTrue(toolDescription(limits, AgentToolNames.READ_DIFF).contains("6"));
    }

    @Test
    void omittedReadStartRemainsAbsentWhileExecutionUsesTheDocumentedDefault() {
        ToolInvocation invocation = parser.parse(AgentToolNames.READ_CODE,
                "{\"version\":\"V1\",\"path\":\"src/A.java\"}", new AgentToolScope("p1",
                        Map.of("V1", SHA), Map.of(), customLimits()));

        assertNull(invocation.start());
        assertNull(invocation.end());
        assertTrue(invocation.fingerprint().contains("|-|-"));
        assertTrue(parameterDescription(customLimits(), AgentToolNames.READ_CODE, "start")
                .contains("1"));
        assertTrue(parameterDescription(customLimits(), AgentToolNames.READ_CODE, "end")
                .contains("119"));
    }

    private static ToolLimitProperties customLimits() {
        ToolLimitProperties limits = new ToolLimitProperties();
        limits.setTreeDefaultDepth(1);
        limits.setTreeMaxDepth(4);
        limits.setTreeDefaultLimit(30);
        limits.setTreeMaxLimit(150);
        limits.setFindDefaultLimit(20);
        limits.setFindMaxLimit(70);
        limits.setSearchDefaultLimit(40);
        limits.setSearchMaxLimit(90);
        limits.setReadDefaultLines(120);
        limits.setReadMaxLines(300);
        limits.setDiffMaxItems(12);
        limits.setDiffMaxHunks(6);
        return limits;
    }

    private static String toolDescription(ToolLimitProperties limits, String tool) {
        return (String) function(limits, tool).get("description");
    }

    private static String parameterDescription(ToolLimitProperties limits, String tool,
                                               String parameter) {
        Map<?, ?> parameters = (Map<?, ?>) function(limits, tool).get("parameters");
        Map<?, ?> properties = (Map<?, ?>) parameters.get("properties");
        return (String) ((Map<?, ?>) properties.get(parameter)).get("description");
    }

    private static Map<?, ?> function(ToolLimitProperties limits, String tool) {
        List<Map<String, Object>> definitions = AgentToolSchema.definitions(limits);
        return definitions.stream()
                .map(definition -> (Map<?, ?>) definition.get("function"))
                .filter(function -> tool.equals(function.get("name")))
                .findFirst().orElseThrow();
    }
}
