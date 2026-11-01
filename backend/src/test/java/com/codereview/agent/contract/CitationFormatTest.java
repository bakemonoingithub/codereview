package com.codereview.agent.contract;

import com.codereview.agent.report.ReportCitationContract;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CitationFormatTest {
    @Test
    void prefixAndDigitLimitDriveExampleSchemaAndRecognition() {
        CitationFormat format = new CitationFormat("X", 2);
        assertEquals("[X1]", format.marker(1));
        assertTrue(format.id(99).matches(format.idPattern()));
        assertTrue(format.id(99).matches(format.schemaPattern()));
        assertFalse(format.id(100).matches(format.idPattern()));
        assertFalse(format.id(100).matches(format.schemaPattern()));
        var matcher = format.markerPattern().matcher("before " + format.marker(100) + " after");
        assertTrue(matcher.find(), "扫描器保留非法长编号供业务层诊断");
        assertEquals("100", matcher.group(1));
        assertFalse(format.markerPattern().matcher("[E1]").find());
    }

    @Test
    void reportExampleIsRecognizedByTheActualValidatorPattern() {
        var matcher = ReportCitationContract.MARKER_PATTERN.matcher(ReportCitationContract.MARKER_EXAMPLE);
        assertTrue(matcher.matches());
        assertEquals("1", matcher.group(1));
    }
}
