package com.codereview.agent.specialist.protocol;

import com.codereview.agent.api.ApiException;
import com.codereview.agent.api.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpecialistResultParserTest {

    private final SpecialistResultParser parser = new SpecialistResultParser();

    @Test
    void parsesIssueResultAndKeepsUnknownFieldsOut() {
        SpecialistResults.Result result = parser.parse("""
                {
                  "summary": "订单写入缺少幂等约束",
                  "issues": [{
                    "category": "CORRECTNESS",
                    "severity": "HIGH",
                    "title": "重复扣款",
                    "locations": [{"materialId": "t1", "startLine": 10, "endLine": 12}],
                    "reason": "重试会再次提交",
                    "suggestion": "增加幂等键",
                    "evidence": [],
                    "changeRelation": null
                  }],
                  "limitations": []
                }
                """, "ISSUE");

        SpecialistResults.IssueResult issues = assertInstanceOf(
                SpecialistResults.IssueResult.class, result);
        assertEquals("订单写入缺少幂等约束", issues.summary());
        assertEquals(1, issues.issues().size());
        assertEquals("CORRECTNESS", issues.issues().get(0).category());
        assertTrue(issues.limitations().isEmpty());
    }

    @Test
    void rejectsMarkdownFence() {
        ApiException failure = assertThrows(ApiException.class,
                () -> parser.parse("```json\n{\"summary\":\"x\",\"issues\":[],\"limitations\":[]}\n```",
                        "ISSUE"));
        assertEquals(ErrorCode.MODEL_OUTPUT_INVALID, failure.errorCode());
        assertEquals("NOT_JSON", code(failure));
    }

    @Test
    void rejectsUnknownFields() {
        ApiException failure = assertThrows(ApiException.class, () -> parser.parse("""
                {"summary":"x","issues":[],"limitations":[],"extra":1}
                """, "ISSUE"));
        assertEquals("UNKNOWN_FIELD", code(failure));
    }

    @Test
    void missingRequiredArrayIsNotTreatedAsEmptyArray() {
        ApiException failure = assertThrows(ApiException.class,
                () -> parser.parse("{\"summary\":\"x\",\"issues\":[]}", "ISSUE"));
        assertEquals("MISSING_FIELD", code(failure));
    }

    @Test
    void issueWithoutLocationsArrayIsRejectedBeforeMapping() {
        ApiException failure = assertThrows(ApiException.class, () -> parser.parse("""
                {"summary":"x","issues":[{"category":"CORRECTNESS","severity":"HIGH",
                "title":"t","reason":"r","suggestion":"s"}],"limitations":[]}
                """, "ISSUE"));
        assertEquals("MISSING_FIELD", code(failure));
    }

    @Test
    void rejectsTrailingText() {
        ApiException failure = assertThrows(ApiException.class, () -> parser.parse("""
                {"summary":"x","issues":[],"limitations":[]} trailing
                """, "ISSUE"));
        assertEquals("INVALID_JSON", code(failure));
    }

    @Test
    void rejectsDuplicateFields() {
        ApiException failure = assertThrows(ApiException.class, () -> parser.parse("""
                {"summary":"x","summary":"y","issues":[],"limitations":[]}
                """, "ISSUE"));
        assertEquals("DUPLICATE_FIELD", code(failure));
    }

    @Test
    void rejectsWrongFieldType() {
        ApiException failure = assertThrows(ApiException.class, () -> parser.parse("""
                {"summary":"x","issues":"no","limitations":[]}
                """, "ISSUE"));
        assertEquals("MISSING_FIELD", code(failure));
    }

    private static String code(ApiException failure) {
        Map<?, ?> details = (Map<?, ?>) failure.details();
        return String.valueOf(details.get("protocolCode"));
    }
}
