package com.codereview.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.codereview.agent.api.ApiException;
import com.codereview.agent.api.ErrorCode;
import com.codereview.agent.db.AgentDatabase;
import com.codereview.agent.entity.AgentReviewResult;
import com.codereview.agent.entity.AgentReviewResultItem;
import com.codereview.agent.mapper.AgentReviewResultMapper;
import com.codereview.agent.mapper.AgentReviewResultItemMapper;
import com.codereview.agent.support.AgentIds;
import com.codereview.agent.support.AgentTime;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 发布、读取和历史回填共用的条目身份校验。读取端绝不临时补建 ID。 */
public final class ResultItemIndex {
    private static final Logger log = LoggerFactory.getLogger(ResultItemIndex.class);
    private final AgentDatabase db;
    private final ObjectMapper mapper;

    public ResultItemIndex(AgentDatabase db, ObjectMapper mapper) {
        this.db = db;
        this.mapper = mapper;
    }

    public static boolean supports(AgentReviewResult result) {
        return "COMPLETE".equals(result.getCompleteness())
                && ("PATTERN".equals(result.getKind()) || "COUPLING".equals(result.getKind()));
    }

    /** 调用方已持有发布事务或结果行锁，保留已存在的 ID，补齐缺失序号。 */
    public int ensure(AgentReviewResult result) {
        if (!supports(result)) return 0;
        int count = itemCount(result);
        List<AgentReviewResultItem> rows = rows(result.getId());
        Set<Integer> ordinals = validate(result, rows, count);
        int added = 0;
        for (int ordinal = 1; ordinal <= count; ordinal++) {
            if (ordinals.contains(ordinal)) continue;
            AgentReviewResultItem item = new AgentReviewResultItem();
            item.setId(AgentIds.next());
            item.setResultId(result.getId());
            item.setKind(result.getKind());
            item.setOrdinal(ordinal);
            item.setCreatedAt(AgentTime.now());
            db.mapper(AgentReviewResultItemMapper.class).insert(item);
            added++;
        }
        return added;
    }

    public List<AgentReviewResultItem> requireItems(AgentReviewResult result) {
        if (!supports(result)) return List.of();
        int count = itemCount(result);
        List<AgentReviewResultItem> rows = rows(result.getId());
        validate(result, rows, count);
        if (rows.size() != count) throw invalid(result, "条目索引缺失，请完成启动回填");
        return rows;
    }

    private List<AgentReviewResultItem> rows(String resultId) {
        return db.mapper(AgentReviewResultItemMapper.class).selectList(
                new LambdaQueryWrapper<AgentReviewResultItem>()
                        .eq(AgentReviewResultItem::getResultId, resultId)
                        .orderByAsc(AgentReviewResultItem::getOrdinal));
    }

    private Set<Integer> validate(AgentReviewResult result, List<AgentReviewResultItem> rows, int count) {
        Set<Integer> ordinals = new HashSet<>();
        for (AgentReviewResultItem row : rows) {
            if (!result.getId().equals(row.getResultId()) || !result.getKind().equals(row.getKind())
                    || row.getId() == null || row.getId().isBlank() || row.getOrdinal() == null || row.getOrdinal() < 1
                    || row.getOrdinal() > count || !ordinals.add(row.getOrdinal())) {
                throw invalid(result, "条目索引类型、序号或归属异常");
            }
        }
        return ordinals;
    }

    private int itemCount(AgentReviewResult result) {
        JsonNode body;
        try {
            body = mapper.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(result.getBody());
        } catch (Exception failure) {
            throw invalid(result, "保存的正文无法解析");
        }
        String field = "PATTERN".equals(result.getKind()) ? "patterns" : "observations";
        String other = "PATTERN".equals(result.getKind()) ? "observations" : "patterns";
        if (body == null || !body.isObject() || !body.path(field).isArray()
                || body.has(other) || body.has("issues")) {
            throw invalid(result, "正文类型或条目数组异常");
        }
        for (JsonNode item : body.path(field)) {
            String title = "PATTERN".equals(result.getKind()) ? "name" : "title";
            if (!item.isObject() || !item.path(title).isTextual()
                    || !item.path("explanation").isTextual() || !item.path("evidence").isArray()) {
                throw invalid(result, "保存的条目结构异常");
            }
        }
        return body.path(field).size();
    }

    /** 在数据库 Bean 返回前执行：此时调度器和 Web 业务均未获得可用数据库。 */
    public BackfillSummary backfill() {
        int scanned = 0, repaired = 0, added = 0;
        String after = null;
        while (true) {
            LambdaQueryWrapper<AgentReviewResult> query = new LambdaQueryWrapper<AgentReviewResult>()
                    .select(AgentReviewResult::getId)
                    .eq(AgentReviewResult::getCompleteness, "COMPLETE")
                    .in(AgentReviewResult::getKind, List.of("PATTERN", "COUPLING"))
                    .orderByAsc(AgentReviewResult::getId).last("LIMIT 100");
            if (after != null) query.gt(AgentReviewResult::getId, after);
            List<AgentReviewResult> batch = db.mapper(AgentReviewResultMapper.class).selectList(query);
            if (batch.isEmpty()) break;
            for (AgentReviewResult candidate : batch) {
                try {
                    int inserted = db.inTransaction(() -> {
                        AgentReviewResult locked = db.mapper(AgentReviewResultItemMapper.class)
                                .lockResult(candidate.getId());
                        if (locked == null || !supports(locked)) {
                            throw invalid(candidate, "回填时结果不存在或已变更");
                        }
                        return ensure(locked);
                    });
                    scanned++;
                    added += inserted;
                    if (inserted > 0) repaired++;
                } catch (RuntimeException failure) {
                    log.error("条目回填失败 resultId={} scanned={} repaired={} unchanged={} added={} failed=1",
                            candidate.getId(), scanned, repaired, scanned - repaired, added);
                    throw failure;
                }
            }
            after = batch.get(batch.size() - 1).getId();
        }
        log.info("条目回填完成 scanned={} repaired={} unchanged={} added={} failed=0",
                scanned, repaired, scanned - repaired, added);
        return new BackfillSummary(scanned, repaired, scanned - repaired, added);
    }

    private ApiException invalid(AgentReviewResult result, String message) {
        return new ApiException(ErrorCode.INTERNAL_ERROR, message + "；结果 ID：" + result.getId());
    }

    public record BackfillSummary(int scanned, int repaired, int unchanged, int added) { }
}
