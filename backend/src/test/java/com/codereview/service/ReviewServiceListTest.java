package com.codereview.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.codereview.common.PageLimits;
import com.codereview.dto.ReviewRecordRow;
import com.codereview.entity.ReviewRecord;
import com.codereview.entity.ReviewStrategy;
import com.codereview.git.GitHostClient;
import com.codereview.mapper.ModelConfigMapper;
import com.codereview.mapper.ProjectMapper;
import com.codereview.mapper.PromptMapper;
import com.codereview.mapper.PromptVersionMapper;
import com.codereview.mapper.ReviewRecordMapper;
import com.codereview.mapper.ReviewStrategyMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 审查记录列表（分页）的服务层契约。
 *
 * <p>守三件事，都是"错了不会报错、只会悄悄出事"的类型：
 * <ol>
 *   <li>列表行**不带** {@code resultJson} / {@code scopeJson} / {@code strategySnapshotJson}；
 *       其中快照 JSON 里含模型明文 apiKey（ReviewService#buildSnapshot），
 *       一旦有人把 list 改回返回裸实体，这里必须失败。</li>
 *   <li>{@code pageSize} 超限被截断 —— 分页拦截器没有 maxLimit，不拦就真去查十万行。</li>
 *   <li>策略名一次批量补齐，而非逐行查询（N+1）。</li>
 * </ol>
 */
class ReviewServiceListTest {

    private ReviewRecordMapper reviewRecordMapper;
    private ReviewStrategyMapper strategyMapper;
    private ReviewService service;

    /**
     * {@code LambdaQueryWrapper} 要靠实体元数据缓存把方法引用翻译成列名，而这份缓存正常是由
     * MyBatis 在装配 mapper 时建立的 —— 纯单测没有容器，必须手工初始化一次，
     * 否则连 {@code select(...)} 都调不通（"can not find lambda cache"）。
     */
    @BeforeAll
    static void initMybatisPlusMetadata() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        assistant.setCurrentNamespace(ReviewRecordMapper.class.getName());
        TableInfoHelper.initTableInfo(assistant, ReviewRecord.class);
    }

    @BeforeEach
    void setUp() {
        reviewRecordMapper = mock(ReviewRecordMapper.class);
        strategyMapper = mock(ReviewStrategyMapper.class);
        service = new ReviewService(reviewRecordMapper, mock(ProjectMapper.class), strategyMapper,
                mock(ModelConfigMapper.class), mock(GitHostClient.class), mock(ReviewExecutor.class),
                mock(PromptMapper.class), mock(PromptVersionMapper.class));
    }

    private static ReviewRecord record(long id, Long strategyId) {
        ReviewRecord r = new ReviewRecord();
        r.setId(id);
        r.setProjectId(9L);
        r.setStrategyId(strategyId);
        r.setBranch("master");
        r.setCommitSha("abcdef1234567890");
        r.setStatus(2);
        r.setProgress(100);
        r.setCreatedAt(LocalDateTime.of(2026, 9, 10, 8, 0, 0));
        // 这三个字段是本次要挡在列表之外的重字段
        r.setResultJson("{\"units\":[{\"raw\":\"巨长的 LLM 原文\"}]}");
        r.setScopeJson("[\"A.java\"]");
        r.setStrategySnapshotJson("{\"model\":{\"apiKey\":\"sk-secret\"}}");
        return r;
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<LambdaQueryWrapper<ReviewRecord>> stubPage(List<ReviewRecord> records,
                                                                     long total, long current, long size) {
        ArgumentCaptor<LambdaQueryWrapper<ReviewRecord>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        when(reviewRecordMapper.selectPage(any(Page.class), captor.capture())).thenAnswer(inv -> {
            Page<ReviewRecord> arg = inv.getArgument(0);
            arg.setRecords(records);
            arg.setTotal(total);
            return arg;
        });
        return captor;
    }

    @Test
    void listReturnsRowsWithoutHeavyFields() {
        stubPage(List.of(record(1001L, 5L), record(1002L, 5L)), 2, 1, 20);
        ReviewStrategy s = new ReviewStrategy();
        s.setId(5L);
        s.setName("变更审查（diff）");
        when(strategyMapper.selectBatchIds(any())).thenReturn(List.of(s));

        Page<ReviewRecordRow> page = service.list(9L, 1, 20);

        assertEquals(2, page.getTotal());
        ReviewRecordRow row = page.getRecords().get(0);
        assertEquals(1001L, row.id());
        assertEquals("master", row.branch());
        assertEquals(2, row.status());
        assertEquals("变更审查（diff）", row.strategyName());
        // 行类型里根本没有这三个字段 —— 这是编译期就已经挡住的，这里断言的是映射结果不为空
        assertNotNull(row.createdAt());
    }

    @Test
    void listSelectsOnlyListColumnsAndNeverTheHeavyOnes() {
        ArgumentCaptor<LambdaQueryWrapper<ReviewRecord>> captor = stubPage(List.of(record(1L, 5L)), 1, 1, 20);
        when(strategyMapper.selectBatchIds(any())).thenReturn(List.of());

        service.list(9L, 1, 20);

        String sql = captor.getValue().getSqlSegment();
        // 列投影：写出来的列必须不含这三个重字段
        assertFalse(sql.contains("result_json"), "列表 SQL 不应 select result_json: " + sql);
        assertFalse(sql.contains("scope_json"), "列表 SQL 不应 select scope_json: " + sql);
        assertFalse(sql.contains("strategy_snapshot_json"),
                "列表 SQL 不应 select strategy_snapshot_json（内含明文 apiKey）: " + sql);
        assertTrue(sql.contains("project_id"), "必须按项目过滤: " + sql);
        assertTrue(sql.contains("ORDER BY created_at DESC"), "必须按创建时间倒序: " + sql);
    }

    @Test
    void listClampsOversizedPageSizeToTheDocumentedCap() {
        stubPage(List.of(), 0, 1, PageLimits.MAX_PAGE_SIZE);
        when(strategyMapper.selectBatchIds(any())).thenReturn(List.of());

        service.list(9L, 1, 100_000);

        ArgumentCaptor<Page<ReviewRecord>> pageCaptor = ArgumentCaptor.forClass(Page.class);
        verify(reviewRecordMapper).selectPage(pageCaptor.capture(), any(LambdaQueryWrapper.class));
        assertEquals(PageLimits.MAX_PAGE_SIZE, pageCaptor.getValue().getSize());
    }

    @Test
    void listFallsBackToOneForNonPositivePageSize() {
        stubPage(List.of(), 0, 1, 1);
        when(strategyMapper.selectBatchIds(any())).thenReturn(List.of());

        service.list(9L, 1, 0);

        ArgumentCaptor<Page<ReviewRecord>> pageCaptor = ArgumentCaptor.forClass(Page.class);
        verify(reviewRecordMapper).selectPage(pageCaptor.capture(), any(LambdaQueryWrapper.class));
        assertEquals(1, pageCaptor.getValue().getSize());
        assertEquals(1, pageCaptor.getValue().getCurrent());
    }

    @Test
    void listLoadsStrategyNamesInOneBatch() {
        stubPage(List.of(record(1L, 5L), record(2L, 5L), record(3L, 7L)), 3, 1, 20);
        when(strategyMapper.selectBatchIds(any())).thenReturn(List.of());

        service.list(9L, 1, 20);

        // 两条记录用同一策略 + 一条用另一策略 → 只应查一次，而不是每行一次
        ArgumentCaptor<List<Long>> idsCaptor = ArgumentCaptor.forClass(List.class);
        verify(strategyMapper).selectBatchIds(idsCaptor.capture());
        assertEquals(List.of(5L, 7L), idsCaptor.getValue());
    }

    @Test
    void listSkipsStrategyLookupWhenPageIsEmpty() {
        stubPage(List.of(), 0, 1, 20);

        Page<ReviewRecordRow> page = service.list(9L, 1, 20);

        assertTrue(page.getRecords().isEmpty());
        verify(strategyMapper, never()).selectBatchIds(any());
    }

    @Test
    void listKeepsRowWhenStrategyWasDeleted() {
        stubPage(List.of(record(1L, 5L)), 1, 1, 20);
        when(strategyMapper.selectBatchIds(any())).thenReturn(List.of());

        Page<ReviewRecordRow> page = service.list(9L, 1, 20);

        // 策略被删掉不能让整页记录消失，只是名字为空
        assertEquals(1, page.getRecords().size());
        assertNull(page.getRecords().get(0).strategyName());
    }
}
