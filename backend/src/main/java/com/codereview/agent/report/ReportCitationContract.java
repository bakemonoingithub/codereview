package com.codereview.agent.report;

import java.util.regex.Pattern;
import com.codereview.agent.contract.CitationFormat;

/**
 * 报告引用标记的单一规则。
 *
 * <p>报告正文使用 {@code [S1]} 形式的来源序号：编号从 1 开始，且不得超过冻结来源数量；
 * 至少需要一处有效引用。协议与系统模板通过 {@link #MARKER_EXAMPLE} 引用本规则，
 * {@link ReportCitationValidator} 使用同一标记模式与最低数量，不再各写一份。
 *
 * <p>只约束标记形式与来源范围，不判断正文结论真伪，也不允许把未知来源改写成最近来源。
 */
public final class ReportCitationContract {

    private static final CitationFormat FORMAT = new CitationFormat("S", null);

    /** 标记示例，进入模型可见正文。 */
    public static final String MARKER_EXAMPLE = FORMAT.marker(1);
    /** 正文引用标记：S 加序号；序号范围由冻结来源数量决定。 */
    public static final Pattern MARKER_PATTERN = FORMAT.markerPattern();
    /** 至少一处有效引用，避免正文看似完整却没有可追溯来源。 */
    public static final int MINIMUM_CITED_SOURCES = 1;

    private ReportCitationContract() {
    }

    public static String citationRequirement() {
        return MINIMUM_CITED_SOURCES == 1 ? "正文必须引用来源序号"
                : "正文必须引用至少 " + MINIMUM_CITED_SOURCES + " 个不同来源序号";
    }

    static String missingCitationMessage() {
        return MINIMUM_CITED_SOURCES == 1 ? "报告正文没有引用任何冻结来源，不能作为完成报告"
                : "报告正文引用的冻结来源少于 " + MINIMUM_CITED_SOURCES + " 个，不能作为完成报告";
    }

    /** 来源序号的合法上界；来源数量在报告生成时冻结。 */
    public static boolean inRange(int ordinal, int sourceCount) {
        return ordinal >= 1 && ordinal <= sourceCount;
    }
}
