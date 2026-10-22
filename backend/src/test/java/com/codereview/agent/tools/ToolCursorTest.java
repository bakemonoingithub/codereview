package com.codereview.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ToolCursorTest {

    private static final String SHA = "b7a312e0c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6";
    private final ToolArgumentParser parser = new ToolArgumentParser(new ObjectMapper());
    private final AgentToolScope scope = AgentToolScope.single("p1", SHA);

    @Test
    void roundTripsTheOffsetForTheSameQuery() {
        ToolInvocation invocation = parse("{\"version\":\"V1\",\"glob\":\"**/*.java\"}");

        String cursor = ToolCursor.encode(invocation, 50);
        ToolCursor decoded = ToolCursor.decode(cursor, invocation);

        assertEquals(50, decoded.offset());
        assertEquals(AgentToolNames.FIND_FILES, decoded.tool());
    }

    @Test
    void rejectsACursorFromAnotherQuery() {
        ToolInvocation java = parse("{\"version\":\"V1\",\"glob\":\"**/*.java\"}");
        ToolInvocation kotlin = parse("{\"version\":\"V1\",\"glob\":\"**/*.kt\"}");
        String cursor = ToolCursor.encode(java, 20);

        ToolFailure failure = assertThrows(ToolFailure.class,
                () -> ToolCursor.decode(cursor, kotlin));

        assertEquals("CURSOR_MISMATCH", failure.code());
    }

    @Test
    void rejectsMalformedAndNegativeCursors() {
        ToolInvocation invocation = parse("{\"version\":\"V1\",\"glob\":\"**/*.java\"}");

        assertEquals("CURSOR_INVALID", assertThrows(ToolFailure.class,
                () -> ToolCursor.decode("not-a-cursor", invocation)).code());

        String negative = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("v1|" + AgentToolNames.FIND_FILES + "|" + ToolCursor.fingerprintOf(invocation)
                        + "|-1").getBytes(StandardCharsets.UTF_8));
        assertEquals("CURSOR_INVALID", assertThrows(ToolFailure.class,
                () -> ToolCursor.decode(negative, invocation)).code());
    }

    @Test
    void blankCursorStartsAtTheFirstPage() {
        ToolInvocation invocation = parse("{\"version\":\"V1\",\"glob\":\"**/*.java\"}");

        assertEquals(0, ToolCursor.decode(null, invocation).offset());
        assertEquals(0, ToolCursor.decode("", invocation).offset());
    }

    private ToolInvocation parse(String arguments) {
        return parser.parse(AgentToolNames.FIND_FILES, arguments, scope);
    }

    @Test
    void fingerprintIsStableAcrossArgumentOrderOnlyWhenSemanticsMatch() {
        ToolInvocation first = new ToolArgumentParser(new ObjectMapper()).parse(
                AgentToolNames.SEARCH_CODE,
                "{\"version\":\"V1\",\"query\":\"abc\",\"extensions\":[\".java\",\".kt\"]}", scope);
        ToolInvocation second = new ToolArgumentParser(new ObjectMapper()).parse(
                AgentToolNames.SEARCH_CODE,
                "{\"version\":\"V1\",\"query\":\"abc\",\"extensions\":[\".kt\",\".java\"]}", scope);

        assertEquals(ToolCursor.fingerprintOf(first), ToolCursor.fingerprintOf(second));
    }
}
