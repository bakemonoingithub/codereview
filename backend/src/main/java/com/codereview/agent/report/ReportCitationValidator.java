package com.codereview.agent.report;

import com.codereview.agent.api.ApiException;
import com.codereview.agent.api.ErrorCode;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;

/**
 * 报告引用校验。
 *
 * <p>标记形式、来源序号范围与最低引用数量来自 {@link ReportCitationContract}，与协议正文
 * 使用同一规则。这里只检查可追溯性，不判断结论真伪。
 *
 * <p>维护关联 BRC-M03：来源资格或冻结语义变化时核对 ReportGenerationService、
 * instruction/report、system/report、protocol/report；位置及回归见 maintenance-map.md。
 */
public final class ReportCitationValidator {

    private ReportCitationValidator() {
    }

    public static int validate(String body, int sourceCount) {
        if (body == null || body.isBlank()) {
            throw new ApiException(ErrorCode.MODEL_OUTPUT_INVALID, "模型没有返回报告正文");
        }
        Matcher matcher = ReportCitationContract.MARKER_PATTERN.matcher(body);
        Set<Integer> cited = new LinkedHashSet<>();
        while (matcher.find()) {
            int ordinal;
            try {
                ordinal = Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException e) {
                throw invalid(matcher.group(), sourceCount);
            }
            if (!ReportCitationContract.inRange(ordinal, sourceCount)) {
                throw invalid(matcher.group(), sourceCount);
            }
            cited.add(ordinal);
        }
        if (cited.size() < ReportCitationContract.MINIMUM_CITED_SOURCES) {
            throw new ApiException(ErrorCode.REPORT_CITATION_INVALID,
                    ReportCitationContract.missingCitationMessage(), Map.of("sourceCount", sourceCount));
        }
        return cited.size();
    }

    private static ApiException invalid(String citation, int sourceCount) {
        return new ApiException(ErrorCode.REPORT_CITATION_INVALID,
                "报告引用了不存在的冻结来源 " + citation + "（共 " + sourceCount + " 个来源）",
                Map.of("citation", citation, "sourceCount", sourceCount));
    }
}
