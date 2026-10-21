package com.codereview.agent.report;

import com.codereview.agent.api.ApiException;
import com.codereview.agent.api.ErrorCode;
import com.codereview.agent.entity.AgentReportSource;
import com.codereview.agent.prompt.PromptFact;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 报告事实只来自冻结快照，引用证据取保存片段，缺失材料明确记录。 */
class ReportFactsBuilderTest {

    private final ObjectMapper json = new ObjectMapper();
    private final ReportFactsBuilder builder = new ReportFactsBuilder(json);

    @Test
    void preservesResultCommentsIncludingUnassessedComments() throws Exception {
        ObjectNode snapshot = (ObjectNode) json.readTree(patternSnapshot());
        assertTrue(builder.build(List.of(source(1, "r2", "PATTERN", snapshot.toString())))
                .facts().get(1).text().contains("需结合调用方确认"));
        ((ObjectNode) snapshot.path("resultFeedback")).putNull("assessment");
        var facts = builder.build(List.of(source(1, "r2", "PATTERN", snapshot.toString())));
        assertEquals(1, facts.summaries().get(0).unassessed());
        assertTrue(facts.facts().get(1).text().contains("未判定（需结合调用方确认）"));
    }

    @Test
    void preservesAuxiliaryEvidenceChangeRelationAndMaterialGaps() throws Exception {
        ObjectNode snapshot = (ObjectNode) json.readTree(issueSnapshot());
        ((ObjectNode) snapshot.path("result").path("body")).putArray("issues").addObject()
                .put("changeRelation", "本次删除幂等保护")
                .putArray("evidence").addObject().put("materialId", "aux").put("startLine", 20).put("endLine", 20);
        ((com.fasterxml.jackson.databind.node.ArrayNode) snapshot.path("materials")).addObject()
                .put("id", "aux").put("path", "Guard.java").put("sha", "base-sha")
                .put("side", "BEFORE").put("startLine", 20).put("endLine", 20).put("content", "guard();");
        ((ObjectNode) snapshot.path("result").path("provenance")).putArray("materialGaps").addObject()
                .put("code", "MISSING").put("subject", "Parent").put("detail", "父类源码未取得");
        var facts = builder.build(List.of(source(1, "r1", "ISSUE", snapshot.toString())));
        String text = facts.facts().get(1).text();
        assertTrue(text.contains("本次删除幂等保护"), text);
        assertTrue(text.contains("guard();"), text);
        assertTrue(text.contains("BEFORE，SHA base-sha"), text);
        assertTrue(facts.missingNotes().get(0).contains("父类源码未取得"));
    }

    @Test
    void neverSubstitutesSamePathForMissingMaterialOrTruncatesEvidence() throws Exception {
        ObjectNode snapshot = (ObjectNode) json.readTree(issueSnapshot());
        ObjectNode location = (ObjectNode) snapshot.path("issues").get(0).path("locations").get(0);
        location.put("materialId", "missing-before");
        var facts = builder.build(List.of(source(1, "r1", "ISSUE", snapshot.toString())));
        assertTrue(!facts.facts().get(1).text().contains("if (retry) charge();"));
        assertTrue(facts.missingNotes().get(0).contains("missing-before"));
        location.put("materialId", "m1").put("endLine", 99);
        facts = builder.build(List.of(source(1, "r1", "ISSUE", snapshot.toString())));
        assertTrue(!facts.facts().get(1).text().contains("if (retry) charge();"));
        assertTrue(facts.missingNotes().get(0).contains("10-99"));
    }

    @Test
    void retainsFullVersionIdentityAndSeparatesSameNamedProjects() throws Exception {
        ObjectNode first = (ObjectNode) json.readTree(patternSnapshot());
        ObjectNode second = first.deepCopy();
        ((ObjectNode) first.path("target")).put("headSha", "123456789012aaaaaaaa");
        ((ObjectNode) second.path("target")).put("headSha", "123456789012bbbbbbbb");
        var facts = builder.build(List.of(source(1, "r2", "PATTERN", first.toString()),
                source(2, "r2", "PATTERN", second.toString())));
        String summary = facts.facts().get(0).text();
        assertTrue(summary.contains("123456789012aaaaaaaa：S1"), summary);
        assertTrue(summary.contains("123456789012bbbbbbbb：S2"), summary);
        assertTrue(facts.facts().get(1).text().contains("123456789012aaaaaaaa"));
    }

    @Test
    void organizesSourcesVersionsFeedbackAndSavedEvidence() throws Exception {
        AgentReportSource issue = source(1, "r1", "ISSUE", issueSnapshot());
        AgentReportSource pattern = source(2, "r2", "PATTERN", patternSnapshot());

        ReportFactsBuilder.ReportFacts facts = builder.build(List.of(issue, pattern));

        assertEquals(2, facts.sourceCount());
        assertEquals(2, facts.taskCount());
        assertEquals(2, facts.versionCount());
        assertEquals(1, facts.kindCounts().get("ISSUE"));
        assertEquals(1, facts.kindCounts().get("PATTERN"));
        assertEquals(2, facts.summaries().size());
        assertEquals(1, facts.summaries().get(0).issueCount());
        assertEquals(1, facts.summaries().get(0).accurate());

        List<PromptFact> promptFacts = facts.facts();
        assertEquals(3, promptFacts.size());
        String summary = promptFacts.get(0).text();
        assertTrue(summary.contains("来源 2 个"), summary);
        assertTrue(summary.contains("仓库版本 2 个"), summary);
        String issueText = promptFacts.get(1).text();
        assertTrue(issueText.contains("重复扣款"), issueText);
        assertTrue(issueText.contains("if (retry) charge();"), issueText);
        assertTrue(issueText.contains("反馈：ACCURATE（已复现）"), issueText);
        String patternText = promptFacts.get(2).text();
        assertTrue(patternText.contains("策略模式"), patternText);
        assertTrue(patternText.contains("结果级反馈：准确 0 / 不准确 0 / 无法判断 1 / 未反馈 0"),
                patternText);
    }

    @Test
    void missingSavedMaterialIsReportedInsteadOfInventingCode() throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode snapshot =
                (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(issueSnapshot());
        snapshot.putArray("materials");
        ReportFactsBuilder.ReportFacts facts = builder.build(List.of(source(1, "r1", "ISSUE", json.writeValueAsString(snapshot))));

        assertTrue(facts.missingNotes().stream().anyMatch(note -> note.contains("未关联保存材料")),
                facts.missingNotes().toString());
        assertTrue(facts.facts().get(1).text().contains("材料缺口"), facts.facts().get(1).text());
    }

    @Test
    void corruptSnapshotFailsInsteadOfBuildingPartialFacts() {
        ApiException error = assertThrows(ApiException.class,
                () -> builder.build(List.of(source(1, "r1", "ISSUE", "{broken"))));

        assertEquals(ErrorCode.REPORT_INPUT_INVALID, error.errorCode());
    }

    @Test
    void emptySourcesCannotProduceReportFacts() {
        ApiException error = assertThrows(ApiException.class,
                () -> builder.build(List.of()));

        assertEquals(ErrorCode.REPORT_INPUT_INVALID, error.errorCode());
    }

    private AgentReportSource source(int ordinal, String resultId, String kind, String snapshot) {
        AgentReportSource row = new AgentReportSource();
        row.setOrdinal(ordinal);
        row.setResultId(resultId);
        row.setSourceSnapshot(snapshot);
        return row;
    }

    private String issueSnapshot() throws Exception {
        return json.writeValueAsString(json.readTree("""
                {
                  "resultId":"r1","taskId":"t1","kind":"ISSUE",
                  "project":{"id":"p1","name":"订单服务","repoUrl":"https://gitea.local/team/order"},
                  "task":{"id":"t1","route":"SPECIALIST","state":"PARTIAL","sourceKind":"FILES","branch":"main","commitSha":"head1","baseSha":null},
                  "target":{"path":"A.java","headSha":"head1","baseSha":null},
                  "result":{"body":{"summary":"重试可能重复扣款","limitations":["未验证数据库隔离级别"]},"provenance":{}},
                  "issues":[{"ordinal":1,"category":"CORRECTNESS","severity":"HIGH","title":"重复扣款",
                    "reason":"重试没有幂等键","suggestion":"增加幂等键",
                    "locations":[{"materialId":"m1","path":"A.java","startLine":10,"endLine":11}],
                    "feedback":{"assessment":"ACCURATE","comment":"已复现"}}],
                  "resultFeedback":null,
                  "materials":[{"id":"m1","path":"A.java","sha":"head1","side":"FILE","startLine":10,"endLine":11,
                    "content":"if (retry) charge();\\nreturn ok;\\n","contentHash":"h1","completeness":"FRAGMENT","diagnostic":null}]
                }
                """));
    }

    private String patternSnapshot() throws Exception {
        return json.writeValueAsString(json.readTree("""
                {
                  "resultId":"r2","taskId":"t2","kind":"PATTERN",
                  "project":{"id":"p2","name":"支付服务","repoUrl":"https://gitea.local/team/pay"},
                  "task":{"id":"t2","route":"SPECIALIST","state":"SUCCEEDED","sourceKind":"FILES","branch":"main","commitSha":"head2","baseSha":null},
                  "target":{"path":"Pay.java","headSha":"head2","baseSha":null},
                  "result":{"body":{"summary":"策略模式候选","patterns":[{"name":"策略模式","family":"BEHAVIORAL","role":"上下文","explanation":"通过接口切换支付渠道","evidence":[{"materialId":"m2","startLine":5,"endLine":6}]}],"limitations":[]},"provenance":{}},
                  "issues":[],
                  "resultFeedback":{"assessment":"UNKNOWN","comment":"需结合调用方确认"},
                  "materials":[{"id":"m2","path":"Pay.java","sha":"head2","side":"FILE","startLine":5,"endLine":6,
                    "content":"interface PayChannel {}\\nclass PayContext {}\\n","contentHash":"h2","completeness":"FRAGMENT","diagnostic":null}]
                }
                """));
    }
}
