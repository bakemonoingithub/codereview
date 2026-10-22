package com.codereview.agent.tools;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentToolSchemaTest {

    @Test
    void exposesExactlyTheFiveReadOnlyTools() {
        List<Map<String, Object>> definitions = AgentToolSchema.definitions();

        assertEquals(5, definitions.size());
        assertEquals(AgentToolNames.READ_ONLY, definitions.stream()
                .map(tool -> ((Map<?, ?>) tool.get("function")).get("name"))
                .toList());
    }

    @Test
    void everySchemaRejectsUnknownFieldsAndDeclaresRequiredArguments() {
        for (Map<String, Object> tool : AgentToolSchema.definitions()) {
            Map<?, ?> function = (Map<?, ?>) tool.get("function");
            Map<?, ?> parameters = (Map<?, ?>) function.get("parameters");
            assertEquals("object", parameters.get("type"));
            assertEquals(false, parameters.get("additionalProperties"), function.get("name").toString());
            assertFalse(((List<?>) parameters.get("required")).isEmpty(), function.get("name").toString());
        }
    }

    @Test
    void limitsInTheSchemaMatchTheRuntimeLimits() {
        Map<String, Map<?, ?>> properties = propertiesByTool();

        Map<?, ?> treeDepth = (Map<?, ?>) properties.get(AgentToolNames.TREE).get("depth");
        assertEquals(1, treeDepth.get("minimum"));
        assertEquals(ToolLimits.TREE_MAX_DEPTH, treeDepth.get("maximum"));
        assertEquals(ToolLimits.TREE_DEFAULT_DEPTH, treeDepth.get("default"));

        Map<?, ?> treeLimit = (Map<?, ?>) properties.get(AgentToolNames.TREE).get("limit");
        assertEquals(ToolLimits.TREE_MAX_LIMIT, treeLimit.get("maximum"));

        Map<?, ?> searchQuery = (Map<?, ?>) properties.get(AgentToolNames.SEARCH_CODE).get("query");
        assertEquals(ToolLimits.SEARCH_QUERY_MAX_CHARS, searchQuery.get("maxLength"));

        Map<?, ?> findGlob = (Map<?, ?>) properties.get(AgentToolNames.FIND_FILES).get("glob");
        assertEquals(ToolLimits.GLOB_MAX_CHARS, findGlob.get("maxLength"));
    }

    @Test
    void schemaVersionIsBoundToTheCacheAndValidationKeys() {
        assertEquals("agent-tools-v1", AgentToolSchema.version());
        assertTrue(AgentToolNames.SCHEMA_VERSION.startsWith("agent-tools-"));
    }

    private static Map<String, Map<?, ?>> propertiesByTool() {
        Map<String, Map<?, ?>> properties = new java.util.LinkedHashMap<>();
        for (Map<String, Object> tool : AgentToolSchema.definitions()) {
            Map<?, ?> function = (Map<?, ?>) tool.get("function");
            Map<?, ?> parameters = (Map<?, ?>) function.get("parameters");
            properties.put((String) function.get("name"), (Map<?, ?>) parameters.get("properties"));
        }
        return properties;
    }
}
