package com.codereview.agent.specialist.protocol;

import com.codereview.agent.contract.ContractField;
import com.codereview.agent.contract.JsonContract;
import com.codereview.agent.prompt.PromptPurpose;
import com.codereview.agent.support.SpecialistTypes;

import java.util.List;

/**
 * 三类专项的输出契约。
 *
 * <p>字段、枚举、必填数组、数量下限与示例只在这里声明一次：
 * {@link com.codereview.agent.prompt.PromptAssemblyService} 用 {@link #forPurpose(PromptPurpose)}
 * 生成模型可见的协议段，{@link SpecialistResultParser} 用 {@link #forType(String)} 做结构检查，
 * {@link SpecialistResultValidator} 只保留材料归属、行号范围、目标依据与依赖事实等业务条件。
 *
 * <p>三类结果继续使用各自的 {@link SpecialistResults} record 作为业务载体；字段清单的一致性
 * 由测试逐项核对，避免契约与载体静默分叉。
 */
public final class SpecialistOutputContracts {

    /** 问题分类；顺序即协议正文中的枚举顺序。 */
    public static final List<String> CATEGORIES = List.of("CORRECTNESS", "SECURITY", "RELIABILITY",
            "PERFORMANCE", "MAINTAINABILITY", "RULE");
    public static final List<String> SEVERITIES = List.of("HIGH", "MEDIUM", "LOW");
    public static final String OTHER_FAMILY = "OTHER";
    public static final List<String> FAMILIES = List.of("CREATIONAL", "STRUCTURAL", "BEHAVIORAL",
            OTHER_FAMILY);

    private static final JsonContract ISSUE = issueContract();
    private static final JsonContract PATTERN = patternContract();
    private static final JsonContract COUPLING = couplingContract();

    private SpecialistOutputContracts() {
    }

    /** 按专项类型取契约；类型来自任务输入，未知类型属于调用错误。 */
    public static JsonContract forType(String specialistType) {
        return switch (specialistType) {
            case SpecialistTypes.ISSUE -> ISSUE;
            case SpecialistTypes.PATTERN -> PATTERN;
            case SpecialistTypes.COUPLING -> COUPLING;
            default -> throw new IllegalArgumentException("未知专项类型：" + specialistType);
        };
    }

    /** 按提示词用途取契约；问题文件与问题差异共用结构，材料侧规则由校验上下文区分。 */
    public static JsonContract forPurpose(PromptPurpose purpose) {
        return switch (purpose) {
            case ISSUE_FILE, ISSUE_DIFF -> ISSUE;
            case PATTERN -> PATTERN;
            case COUPLING -> COUPLING;
            default -> throw new IllegalArgumentException("用途没有专项输出契约：" + purpose);
        };
    }

    /** 仅插入第一档数值和枚举；场景与分析说明保留在模板。 */
    public static java.util.Map<String, String> instructionValues(PromptPurpose purpose) {
        return switch (purpose) {
            case ISSUE_FILE -> {
                var issues = (ContractField.ArrayField) ISSUE.field("issues");
                var item = (ContractField.ObjectField) issues.items();
                var locations = (ContractField.ArrayField) item.fields().stream()
                        .filter(field -> field.name().equals("locations")).findFirst().orElseThrow();
                yield java.util.Map.of("minimumLocations", ContractField.ArrayField.count(locations.minItems()));
            }
            case PATTERN -> java.util.Map.of("minimumTargetEvidence",
                    ContractField.ArrayField.count(SpecialistResultValidator.MINIMUM_PATTERN_TARGETS),
                    "otherFamily", OTHER_FAMILY);
            default -> java.util.Map.of();
        };
    }

    // 维护关联 BRC-M02：以下示例及规则句中的场景语义由 SpecialistResultValidator 校验；
    // 数值/枚举从声明取得，其他文字通过维护索引和业务回归检查一致性。
    private static JsonContract issueContract() {
        List<ContractField> issue = List.of(
                new ContractField.EnumField("category", true, CATEGORIES, null),
                new ContractField.EnumField("severity", true, SEVERITIES, null),
                text("title", true, "简短标题"),
                new ContractField.ArrayField("locations", true, 1, null, targetLocation(),
                        false, false, "至少需要{minZh}个目标位置", null),
                text("reason", true, "可由材料支持的原因"),
                text("suggestion", true, "可执行建议"),
                new ContractField.ArrayField("evidence", false, null, null, auxiliaryLocation(),
                        false, false, null, null),
                new ContractField.TextField("changeRelation", false, false, null, null, null, null,
                        "差异审查必填，文件审查为 null"));
        return new JsonContract("ISSUE", true, List.of(
                text("summary", true, "一句话结论"),
                new ContractField.ArrayField("issues", true, null, null,
                        new ContractField.ObjectField(null, false, issue, null),
                        false, true, null, null),
                limitations()), "位置必须落在对应材料的原始行号范围内");
    }

    private static JsonContract patternContract() {
        List<ContractField> pattern = List.of(
                text("name", true, "模式名"),
                new ContractField.EnumField("family", true, FAMILIES, null),
                text("role", true, "目标承担的角色"),
                text("explanation", true, "可见结构与模式对应关系"),
                new ContractField.ArrayField("evidence", true, 1, null, targetLocation(),
                        false, false, "至少需要{minZh}处代码依据", null));
        return new JsonContract("PATTERN", true, List.of(
                text("summary", true, "一句话结论"),
                new ContractField.ArrayField("patterns", true, null, null,
                        new ContractField.ObjectField(null, false, pattern, null),
                        false, true, null, null),
                limitations()), "每个候选至少" + ContractField.ArrayField.count(SpecialistResultValidator.MINIMUM_PATTERN_TARGETS) + "处目标依据");
    }

    private static JsonContract couplingContract() {
        List<ContractField> observation = List.of(
                text("title", true, "短标题"),
                text("explanation", true, "只解释给定依赖事实"),
                new ContractField.ArrayField("dependencyIds", true, null, null,
                        new ContractField.TextField(null, false, true, null, null, null, null,
                                "dep-1"),
                        false, false, null, null),
                new ContractField.ArrayField("evidence", true, 1, null, targetLocation(),
                        false, false, "至少需要{minZh}处代码依据", null),
                new ContractField.TextField("suggestion", false, false, null, null, null, null,
                        "可选建议"));
        return new JsonContract("COUPLING", true, List.of(
                text("summary", true, "一句话结论"),
                new ContractField.ArrayField("observations", true, null, null,
                        new ContractField.ObjectField(null, false, observation, null),
                        false, true, null, null),
                limitations()), "dependencyIds 必须来自给定事实");
    }

    private static ContractField limitations() {
        return new ContractField.ArrayField("limitations", true, null, null,
                new ContractField.TextField(null, false, true, null, null, null, null, null),
                true, false, null, null);
    }

    private static ContractField targetLocation() {
        return location("target-1", "1", "2");
    }

    private static ContractField auxiliaryLocation() {
        return location("aux-1", "3", "4");
    }

    private static ContractField location(String materialId, String startLine, String endLine) {
        return new ContractField.ObjectField(null, false, List.of(
                new ContractField.TextField("materialId", true, true, null, null, null, null,
                        materialId),
                new ContractField.IntField("startLine", false, 1, null, null, null, startLine),
                new ContractField.IntField("endLine", false, 1, null, null, null, endLine)), null);
    }

    private static ContractField text(String name, boolean required, String example) {
        return new ContractField.TextField(name, required, true, null, null, null, null, example);
    }
}
