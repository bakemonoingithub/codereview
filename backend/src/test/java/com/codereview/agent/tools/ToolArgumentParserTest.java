package com.codereview.agent.tools;

import com.codereview.agent.tools.capability.AgentToolCapabilities;
import com.codereview.agent.tools.capability.ToolCapability;
import com.codereview.agent.tools.capability.ToolParameter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolArgumentParserTest {

    private static final String SHA = "b7a312e0c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6";
    private static final String HEAD = "c1a312e0c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f7";
    private final ToolArgumentParser parser = new ToolArgumentParser(new ObjectMapper());
    private final AgentToolScope scope = new AgentToolScope("p1", Map.of("V1", SHA),
            AgentToolScope.comparisonsOf(new AgentToolScope.Comparison("C1", HEAD, SHA)));

    @Test
    void treeAppliesDocumentedDefaults() {
        ToolInvocation invocation = parser.parse(AgentToolNames.TREE, "{\"version\":\"V1\"}", scope);

        assertEquals(SHA, invocation.sha());
        assertEquals(2, invocation.depth());
        assertEquals(100, invocation.limit());
        assertNull(invocation.path());
        assertNull(invocation.cursor());
    }

    @Test
    void unknownFieldsAreRejectedInsteadOfIgnored() {
        ToolFailure failure = assertThrows(ToolFailure.class,
                () -> parser.parse(AgentToolNames.TREE, "{\"version\":\"V1\",\"bogus\":1}", scope));

        assertEquals("ARGUMENT_UNKNOWN", failure.code());
        assertEquals(ToolEnvelope.INVALID_ARGUMENT, failure.status());
    }

    @Test
    void integersAreStrictlyTypedAndBounded() {
        assertEquals("ARGUMENT_TYPE", assertThrows(ToolFailure.class,
                () -> parser.parse(AgentToolNames.TREE, "{\"version\":\"V1\",\"depth\":\"2\"}", scope)).code());
        assertEquals("ARGUMENT_OUT_OF_RANGE", assertThrows(ToolFailure.class,
                () -> parser.parse(AgentToolNames.TREE, "{\"version\":\"V1\",\"depth\":4}", scope)).code());
        assertEquals("ARGUMENT_OUT_OF_RANGE", assertThrows(ToolFailure.class,
                () -> parser.parse(AgentToolNames.TREE, "{\"version\":\"V1\",\"limit\":201}", scope)).code());
    }

    @Test
    void onlyFrozenVersionRefsAreAccepted() {
        ToolFailure failure = assertThrows(ToolFailure.class,
                () -> parser.parse(AgentToolNames.READ_CODE,
                        "{\"version\":\"V9\",\"path\":\"src/A.java\"}", scope));

        assertEquals("UNKNOWN_VERSION", failure.code());
    }

    @Test
    void pathsCannotEscapeTheRepository() {
        for (String path : List.of("../etc/passwd", "/etc/passwd", "src\\\\A.java", "src/./A.java")) {
            ToolFailure failure = assertThrows(ToolFailure.class,
                    () -> parser.parse(AgentToolNames.READ_CODE,
                            "{\"version\":\"V1\",\"path\":\"" + path.replace("\\", "\\\\") + "\"}", scope));
            assertEquals("ARGUMENT_INVALID", failure.code(), path);
        }
    }

    @Test
    void globMustBeRelativeAndRequired() {
        assertEquals("ARGUMENT_MISSING", assertThrows(ToolFailure.class,
                () -> parser.parse(AgentToolNames.FIND_FILES, "{\"version\":\"V1\"}", scope)).code());
        assertEquals("ARGUMENT_INVALID", assertThrows(ToolFailure.class,
                () -> parser.parse(AgentToolNames.FIND_FILES,
                        "{\"version\":\"V1\",\"glob\":\"**/../*\"}", scope)).code());
        ToolInvocation invocation = parser.parse(AgentToolNames.FIND_FILES,
                "{\"version\":\"V1\",\"glob\":\"src/**/*.java\"}", scope);
        assertEquals("src/**/*.java", invocation.glob());
        assertEquals(50, invocation.limit());
    }

    @Test
    void extensionsAreBoundedAndValidated() {
        ToolInvocation invocation = parser.parse(AgentToolNames.SEARCH_CODE,
                "{\"version\":\"V1\",\"query\":\"needle\",\"extensions\":[\".java\",\"kt\"]}", scope);
        assertEquals(List.of(".java", "kt"), invocation.extensions());

        assertEquals("ARGUMENT_INVALID", assertThrows(ToolFailure.class,
                () -> parser.parse(AgentToolNames.SEARCH_CODE,
                        "{\"version\":\"V1\",\"query\":\"x\",\"extensions\":[\"../evil\"]}", scope)).code());
        assertEquals("ARGUMENT_TOO_LONG", assertThrows(ToolFailure.class,
                () -> parser.parse(AgentToolNames.SEARCH_CODE,
                        "{\"version\":\"V1\",\"query\":\"x\",\"extensions\":[\"a\",\"b\",\"c\",\"d\",\"e\","
                                + "\"f\",\"g\",\"h\",\"i\",\"j\",\"k\"]}", scope)).code());
    }

    @Test
    void readRangesMustBeOrdered() {
        assertEquals("RANGE_INVALID", assertThrows(ToolFailure.class,
                () -> parser.parse(AgentToolNames.READ_CODE,
                        "{\"version\":\"V1\",\"path\":\"A.java\",\"start\":10,\"end\":5}", scope)).code());
    }

    @Test
    void diffOnlyAcceptsKnownComparisons() {
        assertEquals("UNKNOWN_COMPARISON", assertThrows(ToolFailure.class,
                () -> parser.parse(AgentToolNames.READ_DIFF, "{\"comparisonId\":\"C9\"}", scope)).code());

        ToolInvocation invocation = parser.parse(AgentToolNames.READ_DIFF,
                "{\"comparisonId\":\"C1\",\"path\":\"src/A.java\"}", scope);
        assertEquals("C1", invocation.comparisonId());
        assertEquals("src/A.java", invocation.path());
    }

    @Test
    void malformedJsonIsRejected() {
        for (String raw : List.of("[1]", "{}{}", "{\"version\":}", "")) {
            ToolFailure failure = assertThrows(ToolFailure.class,
                    () -> parser.parse(AgentToolNames.TREE, raw, scope), raw);
            assertEquals(ToolEnvelope.INVALID_ARGUMENT, failure.status(), raw);
        }
    }

    @Test
    void nonReadOnlyToolsAreRejected() {
        ToolFailure failure = assertThrows(ToolFailure.class,
                () -> parser.parse(AgentToolNames.SUBMIT_RESULT, "{}", scope));
        assertEquals("UNKNOWN_TOOL", failure.code());
        assertTrue(failure.getMessage().contains(AgentToolNames.SUBMIT_RESULT));
    }

    /** 描述里写出的上限就是解析器实际执行的上限，两处不能各写一套。 */
    @Test
    void documentedBoundsAreTheOnesTheParserEnforces() {
        ToolLimitProperties limits = new ToolLimitProperties();

        assertEquals(limits.getTreeMaxDepth(), parser.parse(AgentToolNames.TREE,
                "{\"version\":\"V1\",\"depth\":" + limits.getTreeMaxDepth() + "}", scope).depth());
        assertEquals("ARGUMENT_OUT_OF_RANGE", assertThrows(ToolFailure.class,
                () -> parser.parse(AgentToolNames.TREE,
                        "{\"version\":\"V1\",\"depth\":" + (limits.getTreeMaxDepth() + 1) + "}", scope)).code());

        assertEquals(limits.getTreeMaxLimit(), parser.parse(AgentToolNames.TREE,
                "{\"version\":\"V1\",\"limit\":" + limits.getTreeMaxLimit() + "}", scope).limit());
        assertEquals("ARGUMENT_OUT_OF_RANGE", assertThrows(ToolFailure.class,
                () -> parser.parse(AgentToolNames.TREE,
                        "{\"version\":\"V1\",\"limit\":" + (limits.getTreeMaxLimit() + 1) + "}", scope)).code());

        assertEquals(limits.getFindMaxLimit(), parser.parse(AgentToolNames.FIND_FILES,
                "{\"version\":\"V1\",\"glob\":\"*.java\",\"limit\":" + limits.getFindMaxLimit() + "}",
                scope).limit());

        assertEquals(limits.getSearchMaxLimit(), parser.parse(AgentToolNames.SEARCH_CODE,
                "{\"version\":\"V1\",\"query\":\"needle\",\"limit\":" + limits.getSearchMaxLimit() + "}",
                scope).limit());

        assertEquals(ToolLimits.SEARCH_MAX_EXTENSIONS, parser.parse(AgentToolNames.SEARCH_CODE,
                "{\"version\":\"V1\",\"query\":\"needle\",\"extensions\":"
                        + extensions(ToolLimits.SEARCH_MAX_EXTENSIONS) + "}", scope).extensions().size());
        assertEquals("ARGUMENT_TOO_LONG", assertThrows(ToolFailure.class,
                () -> parser.parse(AgentToolNames.SEARCH_CODE,
                        "{\"version\":\"V1\",\"query\":\"needle\",\"extensions\":"
                                + extensions(ToolLimits.SEARCH_MAX_EXTENSIONS + 1) + "}", scope)).code());

        assertEquals(ToolLimits.SEARCH_QUERY_MAX_CHARS, parser.parse(AgentToolNames.SEARCH_CODE,
                "{\"version\":\"V1\",\"query\":\"" + "x".repeat(ToolLimits.SEARCH_QUERY_MAX_CHARS) + "\"}",
                scope).query().length());
        assertEquals("ARGUMENT_TOO_LONG", assertThrows(ToolFailure.class,
                () -> parser.parse(AgentToolNames.SEARCH_CODE,
                        "{\"version\":\"V1\",\"query\":\""
                                + "x".repeat(ToolLimits.SEARCH_QUERY_MAX_CHARS + 1) + "\"}", scope)).code());

        assertEquals("ARGUMENT_TOO_LONG", assertThrows(ToolFailure.class,
                () -> parser.parse(AgentToolNames.TREE,
                        "{\"version\":\"V1\",\"path\":\"" + "a".repeat(ToolLimits.PATH_MAX_CHARS + 1) + "\"}",
                        scope)).code());
        assertEquals("ARGUMENT_TOO_LONG", assertThrows(ToolFailure.class,
                () -> parser.parse(AgentToolNames.FIND_FILES,
                        "{\"version\":\"V1\",\"glob\":\"" + "a".repeat(ToolLimits.GLOB_MAX_CHARS + 1) + "\"}",
                        scope)).code());
        assertEquals("ARGUMENT_TOO_LONG", assertThrows(ToolFailure.class,
                () -> parser.parse(AgentToolNames.TREE,
                        "{\"version\":\"" + "V".repeat(17) + "\"}", scope)).code());
        assertEquals("ARGUMENT_TOO_LONG", assertThrows(ToolFailure.class,
                () -> parser.parse(AgentToolNames.TREE,
                        "{\"version\":\"V1\",\"cursor\":\""
                                + "c".repeat(ToolLimits.CURSOR_MAX_CHARS + 1) + "\"}", scope)).code());
    }

    @Test
    void everyReadOnlyCapabilityParameterIsAcceptedByTheParser() {
        ToolLimitProperties limits = new ToolLimitProperties();
        for (ToolCapability capability : AgentToolCapabilities.readOnly(limits)) {
            for (ToolParameter parameter : capability.parameters()) {
                assertTrue(capability.parameterNames().contains(parameter.name()),
                        capability.name() + "/" + parameter.name());
                assertEquals(parameter, capability.parameter(parameter.name()));
            }
        }
    }

    private static String extensions(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(index -> "\".x" + index + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }
}
