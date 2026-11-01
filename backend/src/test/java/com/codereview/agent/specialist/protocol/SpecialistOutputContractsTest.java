package com.codereview.agent.specialist.protocol;

import com.codereview.agent.api.ApiException;
import com.codereview.agent.contract.ContractField;
import com.codereview.agent.contract.JsonContract;
import com.codereview.agent.prompt.PromptAssemblyService;
import com.codereview.agent.prompt.PromptPurpose;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 专项输出契约的同源检查。
 *
 * <p>期望的协议正文来自改造前资源正文，作为独立固定值比较；契约渲染必须逐字相等。
 * 另有载体一致性检查：record 字段与契约字段必须同名同序，避免映射清单静默分叉。
 */
class SpecialistOutputContractsTest {

    private final SpecialistResultParser parser = new SpecialistResultParser();

    @Test
    void rendersTheFrozenProtocolBodiesFromTheContracts() {
        assertEquals("""
                        {"summary":"一句话结论","issues":[{"category":"CORRECTNESS|SECURITY|RELIABILITY|PERFORMANCE|MAINTAINABILITY|RULE","severity":"HIGH|MEDIUM|LOW","title":"简短标题","locations":[{"materialId":"target-1","startLine":1,"endLine":2}],"reason":"可由材料支持的原因","suggestion":"可执行建议","evidence":[{"materialId":"aux-1","startLine":3,"endLine":4}],"changeRelation":"差异审查必填，文件审查为 null"}],"limitations":[]}
                        位置必须落在对应材料的原始行号范围内；issues 可为空；不要输出未知字段。"""
                        .strip(),
                SpecialistOutputContracts.forPurpose(PromptPurpose.ISSUE_FILE).protocolBlock());
        assertEquals(SpecialistOutputContracts.forPurpose(PromptPurpose.ISSUE_FILE).protocolBlock(),
                SpecialistOutputContracts.forPurpose(PromptPurpose.ISSUE_DIFF).protocolBlock());
        assertEquals("""
                        {"summary":"一句话结论","patterns":[{"name":"模式名","family":"CREATIONAL|STRUCTURAL|BEHAVIORAL|OTHER","role":"目标承担的角色","explanation":"可见结构与模式对应关系","evidence":[{"materialId":"target-1","startLine":1,"endLine":2}]}],"limitations":[]}
                        每个候选至少一处目标依据；patterns 可为空；不要输出未知字段。"""
                        .strip(),
                SpecialistOutputContracts.forPurpose(PromptPurpose.PATTERN).protocolBlock());
        assertEquals("""
                        {"summary":"一句话结论","observations":[{"title":"短标题","explanation":"只解释给定依赖事实","dependencyIds":["dep-1"],"evidence":[{"materialId":"target-1","startLine":1,"endLine":2}],"suggestion":"可选建议"}],"limitations":[]}
                        dependencyIds 必须来自给定事实；observations 可为空；不要输出未知字段。"""
                        .strip(),
                SpecialistOutputContracts.forPurpose(PromptPurpose.COUPLING).protocolBlock());
    }

    @Test
    void protocolTextIsTheContractRenderingNotASecondCopy() {
        String system = new PromptAssemblyService().systemPrompt(PromptPurpose.ISSUE_FILE);
        assertTrue(system.contains(SpecialistOutputContracts.forPurpose(PromptPurpose.ISSUE_FILE)
                .protocolBlock()));
        for (String category : SpecialistOutputContracts.CATEGORIES) {
            assertTrue(system.contains(category), category);
        }
        for (String severity : SpecialistOutputContracts.SEVERITIES) {
            assertTrue(system.contains(severity), severity);
        }
    }

    @Test
    void contractsAndResultRecordsDeclareTheSameFieldsInTheSameOrder() {
        JsonContract issue = SpecialistOutputContracts.forType("ISSUE");
        assertEquals(components(SpecialistResults.IssueResult.class), names(issue.fields()));
        JsonContract issueItem = item(issue, "issues");
        assertEquals(components(SpecialistResults.Issue.class), names(issueItem.fields()));
        assertEquals(components(SpecialistResults.Location.class),
                names(item(issueItem, "locations").fields()));

        JsonContract pattern = SpecialistOutputContracts.forType("PATTERN");
        assertEquals(components(SpecialistResults.PatternResult.class), names(pattern.fields()));
        JsonContract patternItem = item(pattern, "patterns");
        assertEquals(components(SpecialistResults.PatternCandidate.class),
                names(patternItem.fields()));
        assertEquals(components(SpecialistResults.Location.class),
                names(item(patternItem, "evidence").fields()));

        JsonContract coupling = SpecialistOutputContracts.forType("COUPLING");
        assertEquals(components(SpecialistResults.CouplingResult.class), names(coupling.fields()));
        JsonContract couplingItem = item(coupling, "observations");
        assertEquals(components(SpecialistResults.CouplingObservation.class),
                names(couplingItem.fields()));
        assertEquals(components(SpecialistResults.Location.class),
                names(item(couplingItem, "evidence").fields()));
    }

    @Test
    void declaredEnumsDriveBothTextAndValidation() {
        JsonContract pattern = SpecialistOutputContracts.forType("PATTERN");
        ContractField.EnumField family = (ContractField.EnumField) item(pattern, "patterns")
                .field("family");
        assertEquals(SpecialistOutputContracts.FAMILIES, family.values());
        assertTrue(pattern.protocolBlock().contains(String.join("|",
                SpecialistOutputContracts.FAMILIES)));

        ApiException failure = assertThrows(ApiException.class, () -> parser.parse("""
                {"summary":"x","patterns":[{"name":"n","family":"MODERN","role":"r",
                "explanation":"e","evidence":[{"materialId":"target-1","startLine":1,"endLine":2}]}],
                "limitations":[]}
                """, "PATTERN"));
        assertEquals("INVALID_ENUM", protocolCode(failure));
    }

    private static JsonContract item(JsonContract contract, String field) {
        ContractField.ArrayField array = (ContractField.ArrayField) contract.field(field);
        return new JsonContract("item", contract.rejectUnknownFields(),
                ((ContractField.ObjectField) array.items()).fields(), null);
    }

    @Test
    void totalEvidenceCountDoesNotRedefineTargetEvidencePolicy() throws Exception {
        JsonContract original = SpecialistOutputContracts.forType("PATTERN");
        var patterns = (ContractField.ArrayField) original.field("patterns");
        var candidate = (ContractField.ObjectField) patterns.items();
        var evidence = (ContractField.ArrayField) item(original, "patterns").field("evidence");
        var twoEvidence = new ContractField.ArrayField(evidence.name(), evidence.required(), 2,
                evidence.maxItems(), evidence.items(), evidence.emptyExample(), evidence.noteEmpty(),
                evidence.tooFewNote(), evidence.description());
        var changedCandidate = new ContractField.ObjectField(null, false, candidate.fields().stream()
                .map(f -> f.name().equals("evidence") ? twoEvidence : f).toList(), null);
        var changedPatterns = new ContractField.ArrayField(patterns.name(), patterns.required(),
                patterns.minItems(), patterns.maxItems(), changedCandidate, patterns.emptyExample(),
                patterns.noteEmpty(), patterns.tooFewNote(), patterns.description());
        var changed = new JsonContract(original.name(), true, original.fields().stream()
                .map(f -> f.name().equals("patterns") ? changedPatterns : f).toList(),
                original.ruleNote());
        // 数组长度是第一档结构规则；其中多少项必须来自目标是独立的业务资格规则。
        assertEquals(original.protocolBlock(), changed.protocolBlock());
        assertEquals("一", SpecialistOutputContracts.instructionValues(PromptPurpose.PATTERN)
                .get("minimumTargetEvidence"));
        var tree = new com.fasterxml.jackson.databind.ObjectMapper().readTree("""
                {"summary":"x","patterns":[{"name":"n","family":"OTHER","role":"r",
                 "explanation":"e","evidence":[{"materialId":"t1","startLine":1,"endLine":2}]}],
                 "limitations":[]}
                """);
        assertTrue(original.checkValues(tree, com.codereview.agent.contract.ContractCodes.SPECIALIST).isEmpty());
        var violations = changed.checkValues(tree, com.codereview.agent.contract.ContractCodes.SPECIALIST);
        assertEquals(1, violations.size());
        assertEquals("MISSING_TARGET", violations.get(0).code());
        assertTrue(violations.get(0).detail().contains("至少需要2处代码依据"));
    }

    @Test
    void nullArrayItemsKeepThePreRefactorProtocolCodes() {
        for (String type : List.of("ISSUE", "PATTERN", "COUPLING")) {
            String collection = Map.of("ISSUE", "issues", "PATTERN", "patterns",
                    "COUPLING", "observations").get(type);
            assertEquals("MISSING_FIELD", protocolCode(assertThrows(ApiException.class,
                    () -> parser.parse("{\"summary\":\"s\",\"" + collection
                            + "\":[null],\"limitations\":[]}", type))));
            assertEquals("INVALID_JSON", protocolCode(assertThrows(ApiException.class,
                    () -> parser.parse("{\"summary\":\"s\",\"" + collection
                            + "\":[],\"limitations\":[null]}", type))));
        }
        assertEquals("INVALID_JSON", protocolCode(assertThrows(ApiException.class,
                () -> parser.parse("""
                {"summary":"s","issues":[{"category":"RULE","severity":"HIGH","title":"t",
                 "reason":"r","suggestion":"s","locations":[null]}],"limitations":[]}
                """, "ISSUE"))));
    }

    private static List<String> names(List<ContractField> fields) {
        return fields.stream().map(ContractField::name).toList();
    }

    private static List<String> components(Class<?> recordType) {
        return Arrays.stream(recordType.getRecordComponents()).map(RecordComponent::getName).toList();
    }

    private static String protocolCode(ApiException failure) {
        return String.valueOf(((Map<?, ?>) failure.details()).get("protocolCode"));
    }
}
