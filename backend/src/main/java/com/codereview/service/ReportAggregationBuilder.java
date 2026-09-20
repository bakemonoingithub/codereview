package com.codereview.service;

import com.codereview.config.ReportProperties;
import com.codereview.entity.ReviewRecord;
import com.codereview.review.ReviewStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 报告聚合文本的构造（纯函数，便于单测）：多条审查记录 → 一段受预算约束的文本。
 *
 * <p>解决的问题：原先{@code ReportExecutor}把**每条记录的完整 {@code result_json}** 原样拼进提示词，
 * 没有任何截断或预算。勾选记录一多，提示词无界增长，最后失败在网关侧（错误信息还不指向根因）。
 *
 * <p>规则（每一条都对应一个测试）：
 * <ol>
 *   <li>按 {@code id} 去重，保留首次出现顺序 —— 重复记录会让报告里的问题数虚高；</li>
 *   <li>跳过失败（status=3）与 {@code result_json} 为空的记录，并在说明里写明跳过了几条；</li>
 *   <li>单条先截到 {@link ReportProperties#getRecordMaxChars()}；</li>
 *   <li>按顺序累加，放不下的**整条省略**（不切半条记录）；</li>
 *   <li>只要发生了裁剪/跳过，就在文本开头写明，并记 WARN；</li>
 *   <li>最后硬截断到额度 —— 保证返回的文本**永远不会超过预算**。</li>
 * </ol>
 */
@Slf4j
@Component
public class ReportAggregationBuilder {

    /** 给"裁剪说明"预留的字符数：说明很短，但要先扣掉，否则说明本身可能把预算撑破。 */
    private static final int NOTICE_RESERVE = 160;

    private final ReportProperties props;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ReportAggregationBuilder(ReportProperties props) {
        this.props = props;
    }

    /**
     * 聚合结果。
     *
     * @param text      受预算约束的聚合文本
     * @param included  实际纳入的记录数
     * @param omitted   因预算不足整条省略的记录数
     * @param truncated 单条被截断的记录数
     * @param skipped   被跳过的记录数（失败 / 无结果）
     */
    public record Result(String text, int included, int omitted, int truncated, int skipped) {

        /** 是否发生过任何裁剪/跳过（提示词里的说明只在这种情况下出现）。 */
        public boolean clipped() {
            return omitted > 0 || truncated > 0 || skipped > 0;
        }
    }

    /**
     * @param records     待聚合的审查记录（顺序即调用方期望的展示顺序）
     * @param budgetChars 聚合文本可用的字符额度（已由调用方扣除模板/补充要求）
     */
    public Result build(List<ReviewRecord> records, int budgetChars) {
        int budget = Math.max(0, budgetChars);
        List<ReviewRecord> unique = dedupe(records);

        List<ReviewRecord> usable = new ArrayList<>();
        int skipped = 0;
        for (ReviewRecord r : unique) {
            if (isUsable(r)) {
                usable.add(r);
            } else {
                skipped++;
            }
        }

        // 先按单条上限截断，得到每条的文本块（块大小已可知，便于按预算累加）
        List<String> blocks = new ArrayList<>(usable.size());
        int truncated = 0;
        for (ReviewRecord r : usable) {
            String block = block(r);
            if (block.length() > props.getRecordMaxChars()) {
                block = block.substring(0, props.getRecordMaxChars()) + "\n…（本条结果已按单条上限截断）\n";
                truncated++;
            }
            blocks.add(block);
        }

        int blocksBudget = Math.max(0, budget - NOTICE_RESERVE);
        StringBuilder included = new StringBuilder();
        int includedCount = 0;
        for (String block : blocks) {
            if (included.length() + block.length() > blocksBudget) {
                break;
            }
            included.append(block);
            includedCount++;
        }
        int omitted = usable.size() - includedCount;

        String text = included.toString();
        if (omitted > 0 || truncated > 0 || skipped > 0) {
            String notice = notice(includedCount, omitted, truncated, skipped);
            text = notice + text;
            log.warn("报告聚合已按预算裁剪：纳入 {} 条 / 省略 {} 条 / 截断 {} 条 / 跳过 {} 条（额度 {} 字符）",
                    includedCount, omitted, truncated, skipped, budget);
        }
        // 硬截断：说明 + 正文仍可能超出（例如额度很小、或说明比预留还长），此时宁可截断也不能超发
        if (text.length() > budget) {
            log.warn("报告聚合文本 {} 字符超过额度 {}，已硬截断", text.length(), budget);
            text = text.substring(0, budget);
        }
        return new Result(text, includedCount, omitted, truncated, skipped);
    }

    /** 按 id 去重，保留首次出现顺序。 */
    private static List<ReviewRecord> dedupe(List<ReviewRecord> records) {
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        Map<Long, ReviewRecord> byId = new LinkedHashMap<>();
        for (ReviewRecord r : records) {
            if (r == null) {
                continue;
            }
            byId.putIfAbsent(r.getId(), r);
        }
        return new ArrayList<>(byId.values());
    }

    /** 可用的聚合素材：成功的、且有结果 JSON 的。 */
    private static boolean isUsable(ReviewRecord r) {
        Integer status = r.getStatus();
        if (status != null && status == ReviewStatus.FAILED) {
            return false;
        }
        String json = r.getResultJson();
        return json != null && !json.isBlank();
    }

    private String block(ReviewRecord r) {
        int type = analyzerType(r);
        JsonNode result = parseJson(r.getResultJson());
        return "### 记录 " + r.getId() + "（" + analyzerName(type) + "）\n"
                + "摘要：" + result.path("summary").asText("") + "\n"
                + "结果 JSON：" + r.getResultJson() + "\n\n";
    }

    private String notice(int included, int omitted, int truncated, int skipped) {
        StringBuilder sb = new StringBuilder("【预算说明】聚合结果已按预算裁剪：");
        sb.append("纳入 ").append(included).append(" 条");
        if (omitted > 0) {
            sb.append("；因超出预算省略 ").append(omitted).append(" 条");
        }
        if (truncated > 0) {
            sb.append("；截断 ").append(truncated).append(" 条（单条上限 ")
                    .append(props.getRecordMaxChars()).append(" 字符）");
        }
        if (skipped > 0) {
            sb.append("；跳过 ").append(skipped).append(" 条无审查结果（失败或空结果）");
        }
        return sb.append("。\n\n").toString();
    }

    private int analyzerType(ReviewRecord r) {
        try {
            JsonNode snap = objectMapper.readTree(r.getStrategySnapshotJson());
            return snap.path("analyzerType").asInt(1);
        } catch (Exception e) {
            return 1;
        }
    }

    private String analyzerName(int type) {
        return switch (type) {
            case 2 -> "coupling";
            case 3 -> "design-pattern";
            case 4 -> "api-review";
            default -> "llm-review";
        };
    }

    private JsonNode parseJson(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }
}
