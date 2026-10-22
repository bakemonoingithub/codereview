package com.codereview.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertEquals("CITATION_INVALID", assertThrows(AgentProtocolException.class,
                () -> AgentSubmission.validate(unknown, sent)).code());

        AgentSubmission outOfRange = AgentSubmission.parse(mapper, """
                {"title":"t","summary":"s","answerStatus":"ANSWERED","markdown":"x [E1]",
                 "citations":[{"id":"E1","materialId":"m1","startLine":19,"endLine":25}]}
                """);
        assertEquals("CITATION_INVALID", assertThrows(AgentProtocolException.class,
                () -> AgentSubmission.validate(outOfRange, sent)).code());
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
}
