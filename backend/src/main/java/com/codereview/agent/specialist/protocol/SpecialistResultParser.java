package com.codereview.agent.specialist.protocol;

import com.codereview.agent.api.ApiException;
import com.codereview.agent.api.ErrorCode;
import com.codereview.agent.support.SpecialistTypes;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import org.springframework.stereotype.Component;

import java.util.Map;

import static com.codereview.agent.specialist.protocol.SpecialistResults.CouplingResult;
import static com.codereview.agent.specialist.protocol.SpecialistResults.IssueResult;
import static com.codereview.agent.specialist.protocol.SpecialistResults.PatternResult;

/**
 * 严格 JSON 解析。
 *
 * <p>拒绝未知字段、重复字段、缺失必填和类型错误；不接受 Markdown 代码围栏或尾随文本。
 * 解析失败只说明“结构不符合协议”，不评价结论对错；调用层据此做最多 2 次格式重试。
 */
@Component
public class SpecialistResultParser {

    private final ObjectMapper strict = strictMapper();

    private static ObjectMapper strictMapper() {
        ObjectMapper mapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .build();
        mapper.coercionConfigFor(LogicalType.Textual)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
        return mapper;
    }

    public SpecialistResults.Result parse(String raw, String specialistType) {
        String text = raw == null ? "" : raw.strip();
        if (text.isEmpty()) {
            throw invalid("EMPTY_OUTPUT", "模型返回为空");
        }
        if (!text.startsWith("{")) {
            throw invalid("NOT_JSON", "模型返回不是 JSON 对象，可能是 Markdown 围栏或额外说明");
        }
        try {
            JsonNode tree = strict.readTree(text);
            if (tree == null || !tree.isObject()) {
                throw invalid("NOT_OBJECT", "模型返回不是 JSON 对象");
            }
            requireRequiredArrays(tree, specialistType);
            return strict.treeToValue(tree, resultClass(specialistType));
        } catch (JsonProcessingException failure) {
            throw invalid(codeOf(failure), failure.getOriginalMessage());
        }
    }

    /**
     * 记录类型的紧凑构造器会把 null 列表收成空列表；必填数组必须在映射前检查，
     * 否则“缺少字段”会被误当成“空数组”。
     */
    private static void requireRequiredArrays(JsonNode tree, String specialistType) {
        requireArray(tree, "limitations");
        switch (specialistType) {
            case SpecialistTypes.ISSUE -> {
                JsonNode issues = requireArray(tree, "issues");
                for (JsonNode issue : issues) {
                    requireArray(issue, "locations");
                }
            }
            case SpecialistTypes.PATTERN -> {
                JsonNode patterns = requireArray(tree, "patterns");
                for (JsonNode pattern : patterns) {
                    requireArray(pattern, "evidence");
                }
            }
            case SpecialistTypes.COUPLING -> {
                JsonNode observations = requireArray(tree, "observations");
                for (JsonNode observation : observations) {
                    requireArray(observation, "dependencyIds");
                    requireArray(observation, "evidence");
                }
            }
            default -> {
            }
        }
    }

    private static JsonNode requireArray(JsonNode parent, String field) {
        JsonNode value = parent.path(field);
        if (!value.isArray()) {
            throw invalid("MISSING_FIELD", field + " 必须是数组");
        }
        return value;
    }

    private static Class<? extends SpecialistResults.Result> resultClass(String specialistType) {
        return switch (specialistType) {
            case SpecialistTypes.ISSUE -> IssueResult.class;
            case SpecialistTypes.PATTERN -> PatternResult.class;
            case SpecialistTypes.COUPLING -> CouplingResult.class;
            default -> throw invalid("UNKNOWN_SPECIALIST", "未知专项类型：" + specialistType);
        };
    }

    private static String codeOf(JsonProcessingException failure) {
        String message = failure.getOriginalMessage() == null ? "" : failure.getOriginalMessage();
        if (message.contains("Unrecognized field")) {
            return "UNKNOWN_FIELD";
        }
        if (message.contains("duplicate") || message.contains("Duplicate")) {
            return "DUPLICATE_FIELD";
        }
        if (failure instanceof com.fasterxml.jackson.databind.exc.MismatchedInputException) {
            return "TYPE_MISMATCH";
        }
        return "INVALID_JSON";
    }

    static ApiException invalid(String code, String detail) {
        return new ApiException(ErrorCode.MODEL_OUTPUT_INVALID,
                "模型输出不符合专项协议：" + detail,
                Map.of("protocolCode", code, "detail", detail == null ? "" : detail));
    }
}
