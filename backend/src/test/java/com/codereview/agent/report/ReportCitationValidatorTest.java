package com.codereview.agent.report;

import com.codereview.agent.api.ApiException;
import com.codereview.agent.api.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 报告引用必须指向冻结来源；未知来源和空引用都明确失败。 */
class ReportCitationValidatorTest {

    @Test
    void acceptsKnownSourceCitations() {
        assertEquals(2, ReportCitationValidator.validate("结论 [S1]，补充 [S2]。", 2));
    }

    @Test
    void rejectsUnknownSourceInsteadOfRewritingIt() {
        ApiException error = assertThrows(ApiException.class,
                () -> ReportCitationValidator.validate("结论 [S3]。", 2));

        assertEquals(ErrorCode.REPORT_CITATION_INVALID, error.errorCode());
    }

    @Test
    void rejectsReportWithoutAnyCitation() {
        ApiException error = assertThrows(ApiException.class,
                () -> ReportCitationValidator.validate("没有引用的报告正文", 2));

        assertEquals(ErrorCode.REPORT_CITATION_INVALID, error.errorCode());
    }

    @Test
    void rejectsEmptyBody() {
        ApiException error = assertThrows(ApiException.class,
                () -> ReportCitationValidator.validate("  ", 1));

        assertEquals(ErrorCode.MODEL_OUTPUT_INVALID, error.errorCode());
    }
}
