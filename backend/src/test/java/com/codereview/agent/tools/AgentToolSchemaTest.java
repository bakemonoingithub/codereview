package com.codereview.agent.tools;

import com.codereview.agent.tools.capability.AgentToolCapabilities;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    void agentExecutionExposesSubmitResultAlongsideReadOnlyTools() {
        List<Map<String, Object>> tools = AgentToolSchema.agentTools();

        assertEquals(6, tools.size());
        Map<String, Object> submit = tools.stream()
                .filter(tool -> ((Map<?, ?>) tool.get("function")).get("name")
                        .equals(AgentToolNames.SUBMIT_RESULT))
                .findFirst().orElseThrow();
        Map<?, ?> parameters = (Map<?, ?>) ((Map<?, ?>) submit.get("function")).get("parameters");
        assertEquals(false, parameters.get("additionalProperties"));
        assertTrue(((List<?>) parameters.get("required")).contains("answerStatus"));
        Map<?, ?> properties = (Map<?, ?>) parameters.get("properties");
        Map<?, ?> citations = (Map<?, ?>) properties.get("citations");
        assertEquals(100, citations.get("maxItems"));
    }

    @Test
    void schemaVersionIsBoundToTheCacheAndValidationKeys() {
        assertTrue(AgentToolSchema.version().startsWith("agent-tools-v2-"));
        assertTrue(AgentToolNames.SCHEMA_VERSION.startsWith("agent-tools-"));
    }

    @Test
    void submissionExamplesPassTheActualParserAndEvidenceValidator() {
        ObjectMapper mapper = new ObjectMapper();
        AgentSubmission answered = AgentSubmission.parse(mapper,
                AgentSubmissionContract.ANSWERED_EXAMPLE.replace("<实际materialId>", "m1"));
        var sent = Map.of("m1", new AgentSubmission.SentMaterial("m1", "src/A.java", "FILE",
                "a".repeat(40), 10, 20, "hash"));
        AgentSubmission checked = AgentSubmission.validate(answered, sent);
        assertTrue(checked.answered());
        assertEquals(1, checked.citations().size());

        AgentSubmission insufficient = AgentSubmission.parse(mapper,
                AgentSubmissionContract.INSUFFICIENT_EVIDENCE_EXAMPLE);
        AgentSubmission withoutEvidence = AgentSubmission.validate(insufficient, Map.of());
        assertFalse(withoutEvidence.answered());
        assertEquals(1, withoutEvidence.unresolved().size());
    }

    @Test
    void submitResultSchemaIsProjectedFromTheSubmissionContract() {
        var contract = AgentSubmissionContract.contract();
        Map<String, Object> submit = AgentToolSchema.agentTools().stream()
                .filter(tool -> ((Map<?, ?>) tool.get("function")).get("name")
                        .equals(AgentToolNames.SUBMIT_RESULT))
                .findFirst().orElseThrow();
        Map<?, ?> properties = (Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) submit.get("function"))
                .get("parameters")).get("properties");

        for (com.codereview.agent.contract.ContractField field : contract.fields()) {
            Map<?, ?> schema = (Map<?, ?>) properties.get(field.name());
            assertTrue(schema != null, field.name());
            if (field instanceof com.codereview.agent.contract.ContractField.TextField text) {
                if (text.schemaMaxLength() != null) {
                    assertEquals(text.schemaMaxLength(), schema.get("maxLength"), field.name());
                }
                if (text.pattern() != null) {
                    assertEquals(text.pattern(), schema.get("pattern"), field.name());
                }
            } else if (field instanceof com.codereview.agent.contract.ContractField.EnumField enumeration) {
                assertEquals(enumeration.values(), schema.get("enum"), field.name());
            } else if (field instanceof com.codereview.agent.contract.ContractField.ArrayField array) {
                assertEquals(array.maxItems(), schema.get("maxItems"), field.name());
                if (array.items() instanceof com.codereview.agent.contract.ContractField.ObjectField item) {
                    Map<?, ?> itemProperties = (Map<?, ?>) ((Map<?, ?>) schema.get("items"))
                            .get("properties");
                    for (com.codereview.agent.contract.ContractField nested : item.fields()) {
                        Map<?, ?> nestedSchema = (Map<?, ?>) itemProperties.get(nested.name());
                        assertTrue(nestedSchema != null, nested.name());
                        if (nested instanceof com.codereview.agent.contract.ContractField.TextField text
                                && text.pattern() != null) {
                            assertEquals(text.pattern(), nestedSchema.get("pattern"), nested.name());
                        }
                    }
                }
            }
        }
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
