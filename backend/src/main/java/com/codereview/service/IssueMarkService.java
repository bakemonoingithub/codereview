package com.codereview.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.codereview.common.BusinessException;
import com.codereview.common.ResultCode;
import com.codereview.dto.AccuracyStat;
import com.codereview.entity.IssueMark;
import com.codereview.entity.ReviewRecord;
import com.codereview.mapper.IssueMarkMapper;
import com.codereview.mapper.ReviewRecordMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * issue 标记与准确率统计（服务验收指标 5）。
 * <p>
 * 指标原文要求"选取两个项目的代码片段进行测试，<b>分别对基础静态规则和 AI 的规则进行复核</b>"，
 * 因此统计按「项目 × 分析器类型」展开，并用 {@code category} 显式区分 static / ai 两类。
 */
@Service
public class IssueMarkService {

    /** 未标记（撤销后的状态） */
    public static final int MARK_NONE = 0;
    /** 误报 */
    public static final int MARK_FALSE_POSITIVE = 1;
    /** 已采纳 */
    public static final int MARK_ACCEPTED = 2;

    private final IssueMarkMapper issueMarkMapper;
    private final ReviewRecordMapper reviewRecordMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public IssueMarkService(IssueMarkMapper issueMarkMapper, ReviewRecordMapper reviewRecordMapper) {
        this.issueMarkMapper = issueMarkMapper;
        this.reviewRecordMapper = reviewRecordMapper;
    }

    /**
     * 打标 / 改标；{@code markValue=0} 表示撤销（= 真删除该行，且语义等幂）。
     * <p>
     * {@code markValue=0} 在"本来就没有标记"时是 no-op（以前会 insert 一行 {@code mark_value=0} 的占位行），
     * 返回 {@code null} 表示当前处于"未标记"状态。
     */
    public IssueMark mark(Long recordId, String unitPath, Integer issueIndex, Integer markValue) {
        if (recordId == null || unitPath == null || unitPath.isBlank() || issueIndex == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "缺少标记定位信息（记录/文件路径/issue 下标）");
        }
        if (markValue == null || markValue < MARK_NONE || markValue > MARK_ACCEPTED) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(),
                    "标记取值必须是 0(未标记)/1(误报)/2(已采纳)");
        }
        if (reviewRecordMapper.selectById(recordId) == null) {
            throw new BusinessException(ResultCode.REVIEW_NOT_FOUND);
        }
        if (markValue == MARK_NONE) {
            unmark(recordId, unitPath, issueIndex);
            return null;
        }
        IssueMark existing = find(recordId, unitPath, issueIndex);
        if (existing == null) {
            IssueMark created = new IssueMark();
            created.setRecordId(recordId);
            created.setUnitPath(unitPath);
            created.setIssueIndex(issueIndex);
            created.setMarkValue(markValue);
            issueMarkMapper.insert(created);
            return created;
        }
        existing.setMarkValue(markValue);
        issueMarkMapper.updateById(existing);
        return existing;
    }

    /**
     * 撤销标记：**真删除**该行（不是写回 0）。
     * <p>
     * 写回 0 的旧做法是为了绕开唯一键 {@code uk_record_unit_issue}：实体带 {@code @TableLogic}，
     * 逻辑删除的行仍然占着唯一键，同一条 issue 就再也标不上。改成真删除后，"占位"问题不存在，
     * 唯一键反而成了"同一单元同一 issue 只有一行"的并发保护（应用层 {@link #find} 查重之外再兜一层）。
     * <p>
     * 本来没有标记时是 no-op（幂等）：重复点"撤销"不会报错，也不会产生垃圾行。
     * <p>
     * 历史说明：{@code V2__issue_mark.sql} 的注释仍写着"撤销写回 0 而不删行"，那是旧口径；
     * 已应用的迁移不动（Flyway 校验 checksum），以本方法为准。
     */
    public void unmark(Long recordId, String unitPath, Integer issueIndex) {
        IssueMark existing = find(recordId, unitPath, issueIndex);
        if (existing == null) {
            return;
        }
        issueMarkMapper.deletePhysically(existing.getId());
    }

    /** 某条记录的标记清单（只返回有效标记）。 */
    public List<IssueMark> list(Long recordId) {
        return issueMarkMapper.selectList(new LambdaQueryWrapper<IssueMark>()
                .eq(IssueMark::getRecordId, recordId)
                .gt(IssueMark::getMarkValue, MARK_NONE)
                .orderByAsc(IssueMark::getUnitPath)
                .orderByAsc(IssueMark::getIssueIndex));
    }

    /** 项目维度的准确率统计。 */
    public List<AccuracyStat> accuracy(Long projectId) {
        List<ReviewRecord> records = reviewRecordMapper.selectList(
                new LambdaQueryWrapper<ReviewRecord>().eq(ReviewRecord::getProjectId, projectId));
        if (records.isEmpty()) {
            return List.of();
        }
        List<Long> recordIds = new ArrayList<>();
        for (ReviewRecord r : records) {
            recordIds.add(r.getId());
        }
        List<IssueMark> marks = issueMarkMapper.selectList(new LambdaQueryWrapper<IssueMark>()
                .in(IssueMark::getRecordId, recordIds)
                .gt(IssueMark::getMarkValue, MARK_NONE));
        return compute(records, marks);
    }

    /**
     * 纯计算（便于单测）：按分析器类型汇总 issue 总数与标记数，算出三个指标。
     * 数组含义：{ 总数, 已标记, 误报, 已采纳 }。
     */
    static List<AccuracyStat> compute(List<ReviewRecord> records, List<IssueMark> marks) {
        ObjectMapper mapper = new ObjectMapper();
        Map<Integer, long[]> agg = new TreeMap<>();
        Map<Long, Integer> typeByRecord = new HashMap<>();

        for (ReviewRecord r : records) {
            int type = analyzerTypeOf(mapper, r);
            typeByRecord.put(r.getId(), type);
            long issues = countIssues(mapper, r.getResultJson());
            if (issues > 0) {
                agg.computeIfAbsent(type, k -> new long[4])[0] += issues;
            }
        }
        if (marks != null) {
            for (IssueMark m : marks) {
                if (m.getMarkValue() == null || m.getMarkValue() <= MARK_NONE) {
                    continue; // 已撤销的标记不计入
                }
                Integer type = typeByRecord.get(m.getRecordId());
                if (type == null) {
                    continue; // 标记指向的记录不在本次统计范围内
                }
                long[] a = agg.computeIfAbsent(type, k -> new long[4]);
                a[1]++;
                if (m.getMarkValue() == MARK_FALSE_POSITIVE) {
                    a[2]++;
                } else if (m.getMarkValue() == MARK_ACCEPTED) {
                    a[3]++;
                }
            }
        }

        List<AccuracyStat> stats = new ArrayList<>();
        for (Map.Entry<Integer, long[]> e : agg.entrySet()) {
            long[] a = e.getValue();
            stats.add(build(e.getKey(), a[0], a[1], a[2], a[3]));
        }
        return stats;
    }

    private static AccuracyStat build(int type, long total, long marked, long falsePositive, long accepted) {
        double overall = total == 0 ? 0d : round((double) (total - falsePositive) / total);
        double reviewed = marked == 0 ? 0d : round((double) accepted / marked);
        double coverage = total == 0 ? 0d : round((double) marked / total);
        return new AccuracyStat(type, analyzerName(type), category(type), total, marked,
                falsePositive, accepted, overall, reviewed, coverage);
    }

    private static int analyzerTypeOf(ObjectMapper mapper, ReviewRecord record) {
        String json = record.getStrategySnapshotJson();
        if (json == null || json.isBlank()) {
            return 0;
        }
        try {
            return mapper.readTree(json).path("analyzerType").asInt(0);
        } catch (Exception e) {
            return 0;
        }
    }

    private static long countIssues(ObjectMapper mapper, String resultJson) {
        if (resultJson == null || resultJson.isBlank()) {
            return 0;
        }
        try {
            JsonNode root = mapper.readTree(resultJson);
            long count = 0;
            for (JsonNode unit : root.path("units")) {
                JsonNode issues = unit.path("issues");
                if (issues.isArray()) {
                    count += issues.size();
                }
            }
            return count;
        } catch (Exception e) {
            return 0;
        }
    }

    /** 指标 5 要求基础静态规则与 AI 规则分别复核，故显式分类。 */
    private static String category(int type) {
        return type == 4 ? "static" : "ai";
    }

    private static String analyzerName(int type) {
        return switch (type) {
            case 1 -> "LLM 审查";
            case 2 -> "耦合度分析";
            case 3 -> "设计模式识别";
            case 4 -> "API 审查（SonarQube）";
            case 5 -> "变更审查（diff）";
            default -> "未知分析器";
        };
    }

    private static double round(double value) {
        return Math.round(value * 10000d) / 10000d;
    }

    private IssueMark find(Long recordId, String unitPath, Integer issueIndex) {
        return issueMarkMapper.selectOne(new LambdaQueryWrapper<IssueMark>()
                .eq(IssueMark::getRecordId, recordId)
                .eq(IssueMark::getUnitPath, unitPath)
                .eq(IssueMark::getIssueIndex, issueIndex));
    }
}
