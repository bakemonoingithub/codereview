package com.codereview.agent.tools;

import com.codereview.agent.contract.*;
import com.codereview.agent.tools.capability.ToolParameter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AgentContractMaintenanceTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void aLengthChangeDrivesBothSchemaAndParsing() throws Exception {
        var original = AgentSubmissionContract.contract();
        var title = new ContractField.TextField("title", true, true, 3, null, null, null);
        var changed = new JsonContract(original.name(), true, original.fields().stream()
                .map(f -> f.name().equals("title") ? title : f).toList(), null);
        var schema = (ToolParameter.Text) AgentSubmissionContract.tool(changed).parameters().get(0);
        assertEquals(3, schema.schemaMaxLength());
        assertFalse(changed.checkValues(mapper.readTree("""
                {"title":"four","summary":"s","answerStatus":"ANSWERED","markdown":"m"}
                """), ContractCodes.AGENT_SUBMISSION).isEmpty());
        assertTrue(changed.checkValues(mapper.readTree("""
                {"title":"yes","summary":"s","answerStatus":"ANSWERED","markdown":"m"}
                """), ContractCodes.AGENT_SUBMISSION).isEmpty());
        var citations = (ContractField.ArrayField) original.field("citations");
        var material = (ContractField.TextField) ((ContractField.ObjectField) citations.items()).fields().get(1);
        assertNull(material.maxChars());
        assertEquals(26, material.schemaMaxLength(), "保留材料 ID 的历史 Schema 差异");
    }

    @Test
    void nullCitationRemainsAParseError() {
        AgentProtocolException failure = assertThrows(AgentProtocolException.class,
                () -> AgentSubmission.parse(mapper, """
                {"title":"t","summary":"s","answerStatus":"INSUFFICIENT_EVIDENCE",
                 "markdown":"m","citations":[null],"unresolved":["missing"]}
                """));
        assertEquals("RESULT_INVALID", failure.code());
        assertTrue(failure.getMessage().contains("citations[0]"));
    }
}
