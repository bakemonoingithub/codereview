package com.codereview.agent.report;

import com.codereview.agent.api.ApiException;
import com.codereview.agent.api.ErrorCode;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 报告引用校验。
 *
 * <p>只接受统一组装协议约定的 {@code [S1]} 形式；引用序号必须落在冻结来源范围内。
 * 至少要求一处引用，避免正文看似完整却没有可追溯来源。这里不判断结论真伪，
 * 也不允许把未知来源自动改写成最近来源。
 */
public final class ReportCitationValidator {

    private static final Pattern CITATION = Pattern.compile("\\[S(\\d+)]");

    private ReportCitationValidator() {
    }

    public static int validate(String body, int sourceCount) {
        if (body == null || body.isBlank()) {
            throw new ApiException(ErrorCode.MODEL_OUTPUT_INVALID, "模型没有返回报告正文");
        }
        Matcher matcher = CITATION.matcher(body);
        Set<Integer> cited = new LinkedHashSet<>();
        while (matcher.find()) {
            int ordinal;
            try {
                ordinal = Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException e) {
                throw invalid(matcher.group(), sourceCount);
            }
            if (ordinal < 1 || ordinal > sourceCount) {
                throw invalid(matcher.group(), sourceCount);
            }
            cited.add(ordinal);
        }
        if (cited.isEmpty()) {
            throw new ApiException(ErrorCode.REPORT_CITATION_INVALID,
                    "报告正文没有引用任何冻结来源，不能作为完成报告", Map.of("sourceCount", sourceCount));
        }
        return cited.size();
    }

    private static ApiException invalid(String citation, int sourceCount) {
        return new ApiException(ErrorCode.REPORT_CITATION_INVALID,
                "报告引用了不存在的冻结来源 " + citation + "（共 " + sourceCount + " 个来源）",
                Map.of("citation", citation, "sourceCount", sourceCount));
    }
}
