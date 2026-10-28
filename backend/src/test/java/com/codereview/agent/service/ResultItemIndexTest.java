package com.codereview.agent.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.codereview.agent.api.ApiException;
import com.codereview.agent.db.AgentDatabase;
import com.codereview.agent.entity.AgentReviewResult;
import com.codereview.agent.entity.AgentReviewResultItem;
import com.codereview.agent.mapper.AgentReviewResultMapper;
import com.codereview.agent.mapper.AgentReviewResultItemMapper;
import com.codereview.agent.support.AgentIds;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ResultItemIndexTest {
    private final AgentDatabase db = mock(AgentDatabase.class);
    private final AgentReviewResultMapper results = mock(AgentReviewResultMapper.class);
    private final AgentReviewResultItemMapper items = mock(AgentReviewResultItemMapper.class);
    private final List<AgentReviewResultItem> stored = new ArrayList<>();
    private final ResultItemIndex index = new ResultItemIndex(db, new ObjectMapper());

    @BeforeEach
    void setup() {
        for (var type : List.of(AgentReviewResult.class, AgentReviewResultItem.class)) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), type);
        }
        when(db.mapper(AgentReviewResultMapper.class)).thenReturn(results);
        when(db.mapper(AgentReviewResultItemMapper.class)).thenReturn(items);
        when(db.inTransaction(any())).thenAnswer(call -> ((Supplier<?>) call.getArgument(0)).get());
        when(items.selectList(any())).thenAnswer(call -> List.copyOf(stored));
        when(items.insert(any(AgentReviewResultItem.class))).thenAnswer(call -> {
            stored.add(call.getArgument(0)); return 1;
        });
    }

    @Test
    void backfillAdvancesTheIdCursorAcrossBatchBoundariesWithoutSkippingEmptyResults() {
        var history = java.util.stream.IntStream.range(0, 101).mapToObj(i -> result("PATTERN", 0))
                .sorted(java.util.Comparator.comparing(AgentReviewResult::getId)).toList();
        when(results.selectList(any())).thenReturn(history.subList(0, 100), history.subList(100, 101), List.of());
        when(items.lockResult(anyString())).thenAnswer(call -> history.stream()
                .filter(row -> row.getId().equals(call.getArgument(0))).findFirst().orElseThrow());
        assertEquals(new ResultItemIndex.BackfillSummary(101, 0, 101, 0), index.backfill());
        var queries = org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);
        verify(results, times(3)).selectList(queries.capture());
        var first = queries.getAllValues().get(0); var second = queries.getAllValues().get(1);
        assertTrue(first.getSqlSegment().contains("LIMIT 100"));
        assertTrue(second.getSqlSegment().contains("id >"));
        assertTrue(second.getParamNameValuePairs().containsValue(history.get(99).getId()));
        verify(db, times(101)).inTransaction(any());
        verify(items, never()).insert(any(AgentReviewResultItem.class));
    }

    @Test
    void repeatedTitlesRemainIndependentAndRepeatedEnsurePreservesIds() {
        var result = result("PATTERN", 2);
        assertEquals(2, index.ensure(result));
        List<String> ids = stored.stream().map(AgentReviewResultItem::getId).toList();
        assertNotEquals(ids.get(0), ids.get(1));
        assertEquals(List.of(1, 2), stored.stream().map(AgentReviewResultItem::getOrdinal).toList());
        assertEquals(0, index.ensure(result));
        assertEquals(ids, index.requireItems(result).stream().map(AgentReviewResultItem::getId).toList());
    }

    @Test
    void repairsHolesWithoutReplacingExistingIds() {
        var result = result("COUPLING", 3);
        var existing = item(result, 2);
        stored.add(existing);
        assertEquals(2, index.ensure(result));
        assertEquals(existing.getId(), stored.get(0).getId());
        assertEquals(3, index.requireItems(result).size());
    }

    @Test
    void readWithMissingIndexFailsWithoutWriting() {
        var error = assertThrows(ApiException.class, () -> index.requireItems(result("PATTERN", 1)));
        assertTrue(error.getMessage().contains("索引缺失"));
        verify(items, never()).insert(any(AgentReviewResultItem.class));
    }

    @Test
    void rejectsExtraOrdinalWrongKindAndDuplicateOrdinalBeforeAddingAnything() {
        var result = result("PATTERN", 2);
        var row = item(result, 3); stored.add(row);
        assertThrows(ApiException.class, () -> index.ensure(result));
        row.setOrdinal(1); row.setKind("COUPLING");
        assertThrows(ApiException.class, () -> index.ensure(result));
        row.setKind("PATTERN"); stored.add(item(result, 1));
        assertThrows(ApiException.class, () -> index.ensure(result));
        verify(items, never()).insert(any(AgentReviewResultItem.class));
    }

    @Test
    void zeroItemsAreValidAndIncompleteIssueAgentNeverAcquireItems() {
        assertEquals(List.of(), index.requireItems(result("PATTERN", 0)));
        assertEquals(0, index.ensure(result("COUPLING", 0)));
        var incomplete = result("PATTERN", 1); incomplete.setCompleteness("INCOMPLETE");
        assertEquals(List.of(), index.requireItems(incomplete));
        assertEquals(0, index.ensure(incomplete));
        assertEquals(0, index.ensure(result("ISSUE", 1)));
        assertEquals(0, index.ensure(result("AGENT", 1)));
        verify(items, never()).insert(any(AgentReviewResultItem.class));
    }

    @Test
    void corruptBodyAndWrongBodyKindNeverBecomeEmptyResults() {
        var result = result("PATTERN", 1);
        for (String body : List.of("broken", "{}", "{\"patterns\":null}",
                "{\"observations\":[]}", "{\"patterns\":[null]}",
                "{\"patterns\":[{\"name\":\"same\"}]}")) {
            result.setBody(body);
            assertThrows(ApiException.class, () -> index.ensure(result), body);
        }
        verify(items, never()).insert(any(AgentReviewResultItem.class));
    }

    @Test
    void emptyDatabaseFinishesWithoutOpeningAResultTransaction() {
        when(results.selectList(any())).thenReturn(List.of());
        assertEquals(new ResultItemIndex.BackfillSummary(0, 0, 0, 0), index.backfill());
        verify(db, never()).inTransaction(any());
    }

    @Test
    void backfillLocksEachResultAndRetryPreservesCommittedIds() {
        var first = result("PATTERN", 2);
        var second = result("COUPLING", 1);
        // 模拟上一份已提交，而后一份在读取前中断；重启仍扫描已完成记录并核对。
        when(results.selectList(any())).thenReturn(List.of(first, second));
        when(items.lockResult(first.getId())).thenReturn(first);
        when(items.lockResult(second.getId())).thenThrow(new IllegalStateException("interrupted"));
        assertThrows(IllegalStateException.class, index::backfill);
        var ids = stored.stream().map(AgentReviewResultItem::getId).toList();
        when(results.selectList(any())).thenReturn(List.of(first), List.of());
        assertEquals(new ResultItemIndex.BackfillSummary(1, 0, 1, 0), index.backfill());
        assertEquals(ids, stored.stream().map(AgentReviewResultItem::getId).toList());
        verify(items, times(2)).lockResult(first.getId());
    }

    @Test
    void invalidHistoricalResultStopsBackfillWithItsId() {
        var result = result("PATTERN", 1); result.setBody("broken");
        when(results.selectList(any())).thenReturn(List.of(result));
        when(items.lockResult(result.getId())).thenReturn(result);
        assertTrue(assertThrows(ApiException.class, index::backfill).getMessage().contains(result.getId()));
    }

    static AgentReviewResult result(String kind, int count) {
        var result = new AgentReviewResult();
        result.setId(AgentIds.next()); result.setKind(kind); result.setCompleteness("COMPLETE");
        String item = "PATTERN".equals(kind)
                ? "{\"name\":\"same\",\"family\":\"OTHER\",\"role\":\"role\",\"explanation\":\"why\",\"evidence\":[]}"
                : "{\"title\":\"same\",\"explanation\":\"why\",\"dependencyIds\":[],\"evidence\":[]}";
        result.setBody("{\"" + ("PATTERN".equals(kind) ? "patterns" : "observations") + "\":["
                + String.join(",", java.util.Collections.nCopies(count, item)) + "]}");
        return result;
    }

    static AgentReviewResultItem item(AgentReviewResult result, int ordinal) {
        var row = new AgentReviewResultItem(); row.setId(AgentIds.next());
        row.setResultId(result.getId()); row.setKind(result.getKind()); row.setOrdinal(ordinal);
        return row;
    }
}
