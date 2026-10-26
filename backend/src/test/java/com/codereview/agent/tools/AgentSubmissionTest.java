package com.codereview.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentSubmissionTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, AgentSubmission.SentMaterial> sent = Map.of(
            "m1", new AgentSubmission.SentMaterial("m1", "src/A.java", "FILE",
                    "b7a312e0c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6", 10, 20, "hash"));

    @Test
    void parsesAValidAnsweredSubmission() {
        AgentSubmission submission = AgentSubmission.parse(mapper, """
                {"title":"重试链路","summary":"调用方缺少幂等保证。","answerStatus":"ANSWERED",
                 "markdown":"结论。[E1]","citations":[{"id":"E1","materialId":"m1",
                 "startLine":12,"endLine":13}],"unresolved":[]}
                """);

        assertEquals(AgentSubmission.ANSWERED, submission.answerStatus());
        assertEquals(1, submission.citations().size());
        AgentSubmission checked = AgentSubmission.validate(submission, sent);
        assertEquals(1, checked.citations().size());
    }

    @Test
    void unknownFieldsAndBadStatusesAreRejected() {
        assertThrows(AgentProtocolException.class, () -> AgentSubmission.parse(mapper, """
                {"title":"t","summary":"s","answerStatus":"ANSWERED","markdown":"m","extra":1}
                """));
        assertThrows(AgentProtocolException.class, () -> AgentSubmission.parse(mapper, """
                {"title":"t","summary":"s","answerStatus":"MAYBE","markdown":"m"}
                """));
    }

    @Test
    void citationsMustPointAtSentMaterialsWithinTheirLineRange() {
        AgentSubmission unknown = AgentSubmission.parse(mapper, """
                {"title":"t","summary":"s","answerStatus":"ANSWERED","markdown":"x [E1]",
                 "citations":[{"id":"E1","materialId":"missing","startLine":1,"endLine":1}]}
                """);
        AgentProtocolException missing = assertThrows(AgentProtocolException.class,
                () -> AgentSubmission.validate(unknown, sent));
        assertEquals("CITATION_INVALID", missing.code());
        assertTrue(missing.getMessage().contains("本次任务"), missing.getMessage());
        assertTrue(missing.getMessage().contains("materialId"), missing.getMessage());

        AgentSubmission outOfRange = AgentSubmission.parse(mapper, """
                {"title":"t","summary":"s","answerStatus":"ANSWERED","markdown":"x [E1]",
                 "citations":[{"id":"E1","materialId":"m1","startLine":19,"endLine":25}]}
                """);
        AgentProtocolException range = assertThrows(AgentProtocolException.class,
                () -> AgentSubmission.validate(outOfRange, sent));
        assertEquals("CITATION_INVALID", range.code());
        assertTrue(range.getMessage().contains("材料范围 10-20"), range.getMessage());
        assertTrue(range.getMessage().contains("read_code"), range.getMessage());
        assertTrue(range.getMessage().contains("materialId=m1"), range.getMessage());
    }

    @Test
    void reportsAllInvalidCitationRangesTogether() {
        AgentSubmission submission = AgentSubmission.parse(mapper, """
                {"title":"t","summary":"s","answerStatus":"ANSWERED",
                 "markdown":"[E1] [E2]",
                 "citations":[
                   {"id":"E1","materialId":"m1","startLine":1,"endLine":2},
                   {"id":"E2","materialId":"m1","startLine":25,"endLine":26}]}
                """);

        AgentProtocolException error = assertThrows(AgentProtocolException.class,
                () -> AgentSubmission.validate(submission, sent));

        assertEquals("CITATION_INVALID", error.code());
        assertTrue(error.getMessage().contains("E1"), error.getMessage());
        assertTrue(error.getMessage().contains("E2"), error.getMessage());
    }

    @Test
    void reportsMultipleMalformedCitationIdsTogether() {
        AgentSubmission submission = AgentSubmission.parse(mapper, """
                {"title":"t","summary":"s","answerStatus":"ANSWERED",
                 "markdown":"x", "citations":[
                   {"id":"S1","materialId":"m1","startLine":10,"endLine":10},
                   {"id":"S2","materialId":"m1","startLine":11,"endLine":11}]}
                """);
        AgentProtocolException error = assertThrows(AgentProtocolException.class,
                () -> AgentSubmission.validate(submission, sent));

        assertEquals("CITATION_INVALID", error.code());
        assertTrue(error.getMessage().contains("S1"), error.getMessage());
        assertTrue(error.getMessage().contains("S2"), error.getMessage());
    }

    @Test
    void includesEveryCitationErrorWithinTheSchemaLimit() {
        StringBuilder citations = new StringBuilder();
        for (int index = 1; index <= 12; index++) {
            if (index > 1) citations.append(',');
            citations.append("{\"id\":\"S").append(index)
                    .append("\",\"materialId\":\"m1\",\"startLine\":10,\"endLine\":10}");
        }
        AgentSubmission submission = AgentSubmission.parse(mapper,
                "{\"title\":\"t\",\"summary\":\"s\",\"answerStatus\":\"ANSWERED\","
                        + "\"markdown\":\"x\",\"citations\":[" + citations + "]}");

        AgentProtocolException error = assertThrows(AgentProtocolException.class,
                () -> AgentSubmission.validate(submission, sent));

        assertTrue(error.getMessage().contains("S12"), error.getMessage());
        assertFalse(error.getMessage().contains("另有"), error.getMessage());
    }

    @Test
    void combinesMalformedIdsAndOutOfRangeCitationsInOneDiagnostic() {
        AgentSubmission submission = AgentSubmission.parse(mapper, """
                {"title":"t","summary":"s","answerStatus":"ANSWERED",
                 "markdown":"x", "citations":[
                   {"id":"S1","materialId":"m1","startLine":10,"endLine":10},
                   {"id":"E2","materialId":"m1","startLine":25,"endLine":26}]}
                """);
        AgentProtocolException error = assertThrows(AgentProtocolException.class,
                () -> AgentSubmission.validate(submission, sent));

        assertTrue(error.getMessage().contains("S1"), error.getMessage());
        assertTrue(error.getMessage().contains("E2"), error.getMessage());
        assertTrue(error.getMessage().contains("材料范围 10-20"), error.getMessage());
    }

    @Test
    void markdownMarkersMustResolveToDeclaredCitations() {
        AgentSubmission unknownMarker = AgentSubmission.parse(mapper, """
                {"title":"t","summary":"s","answerStatus":"ANSWERED","markdown":"x [E2]",
                 "citations":[{"id":"E1","materialId":"m1","startLine":10,"endLine":11}]}
                """);
        assertEquals("CITATION_INVALID", assertThrows(AgentProtocolException.class,
                () -> AgentSubmission.validate(unknownMarker, sent)).code());
    }

    @Test
    void unusedCitationsAreDroppedAndAnsweredNeedsAtLeastOne() {
        AgentSubmission submission = AgentSubmission.parse(mapper, """
                {"title":"t","summary":"s","answerStatus":"ANSWERED","markdown":"x [E1]",
                 "citations":[{"id":"E1","materialId":"m1","startLine":10,"endLine":10},
                              {"id":"E2","materialId":"m1","startLine":11,"endLine":11}]}
                """);
        AgentSubmission checked = AgentSubmission.validate(submission, sent);
        assertEquals(List.of("E1"), checked.citations().stream()
                .map(AgentSubmission.Citation::id).toList());

        AgentSubmission noCitation = AgentSubmission.parse(mapper, """
                {"title":"t","summary":"s","answerStatus":"ANSWERED","markdown":"没有引用"}
                """);
        assertThrows(AgentProtocolException.class,
                () -> AgentSubmission.validate(noCitation, sent));
    }

    @Test
    void insufficientEvidenceRequiresUnresolvedItems() {
        AgentSubmission missing = AgentSubmission.parse(mapper, """
                {"title":"t","summary":"s","answerStatus":"INSUFFICIENT_EVIDENCE",
                 "markdown":"缺口。","unresolved":[]}
                """);
        assertThrows(AgentProtocolException.class,
                () -> AgentSubmission.validate(missing, sent));

        AgentSubmission ok = AgentSubmission.parse(mapper, """
                {"title":"t","summary":"s","answerStatus":"INSUFFICIENT_EVIDENCE",
                 "markdown":"缺口。","unresolved":["无法确认支付实现。"]}
                """);
        assertTrue(AgentSubmission.validate(ok, sent).citations().isEmpty());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = { "结论（E1）", "见 E1", "结论。[E1,E2]" })
    void suppliedCitationsWithoutMarkdownMarkersExplainTheRequiredFormat(String markdown) {
        AgentSubmission submission = new AgentSubmission("t", "s", "ANSWERED", markdown,
                List.of(new AgentSubmission.Citation("E1", "m1", 12, 13)), List.of());

        AgentProtocolException error = assertThrows(AgentProtocolException.class,
                () -> AgentSubmission.validate(submission, sent));

        assertEquals("CITATION_INVALID", error.code());
        assertTrue(error.getMessage().contains("citations 已提供"), error.getMessage());
        assertTrue(error.getMessage().contains("markdown"), error.getMessage());
        assertTrue(error.getMessage().contains("[E1]"), error.getMessage());
        assertFalse(error.getMessage().contains("citations 为空"), error.getMessage());
    }

    @Test
    void missingCitationsExplainHowToDeclareRealEvidence() {
        AgentSubmission submission = new AgentSubmission("t", "s", "ANSWERED", "结论。", List.of(), List.of());

        AgentProtocolException error = assertThrows(AgentProtocolException.class,
                () -> AgentSubmission.validate(submission, sent));

        assertEquals("CITATION_INVALID", error.code());
        assertTrue(error.getMessage().contains("citations 为空"), error.getMessage());
        assertTrue(error.getMessage().contains("materialId"), error.getMessage());
        assertTrue(error.getMessage().contains("[E1]"), error.getMessage());
    }

    @Test
    void duplicateCitationIdsAreRejected() {
        AgentSubmission submission = AgentSubmission.parse(mapper, """
                {"title":"t","summary":"s","answerStatus":"ANSWERED","markdown":"x [E1]",
                 "citations":[{"id":"E1","materialId":"m1","startLine":10,"endLine":10},
                              {"id":"E1","materialId":"m1","startLine":11,"endLine":11}]}
                """);
        AgentProtocolException error = assertThrows(AgentProtocolException.class,
                () -> AgentSubmission.validate(submission, sent));
        assertEquals("CITATION_INVALID", error.code());
    }

    @Test
    void fullCitationBatchKeepsCorrectionHintsWithinTheToolEnvelopeLimit() {
        var citations = java.util.stream.IntStream.rangeClosed(1, 100)
                .mapToObj(index -> new AgentSubmission.Citation("E" + index, "m1", 25, 26)).toList();
        String markdown = java.util.stream.IntStream.rangeClosed(1, 100)
                .mapToObj(index -> "[E" + index + "]").collect(java.util.stream.Collectors.joining());
        AgentSubmission submission = new AgentSubmission("t", "s", "ANSWERED", markdown, citations, List.of());

        AgentProtocolException error = assertThrows(AgentProtocolException.class,
                () -> AgentSubmission.validate(submission, sent));
        ToolEnvelope envelope = ToolEnvelope.failure("tool-1",
                ToolFailure.invalid("CITATION_INVALID", error.getMessage()),
                AgentToolScope.single("p1", "a".repeat(40)));

        assertTrue(error.getMessage().contains("read_code"), error.getMessage());
        assertEquals(error.getMessage().indexOf("read_code"), error.getMessage().lastIndexOf("read_code"));
        assertTrue(ToolEnvelopes.size(mapper, envelope) <= ToolLimits.ENVELOPE_MAX_BYTES);
    }
}
