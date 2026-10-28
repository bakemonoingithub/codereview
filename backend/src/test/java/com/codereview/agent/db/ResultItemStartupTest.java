package com.codereview.agent.db;

import com.codereview.agent.mapper.AgentReviewResultItemMapper;
import com.codereview.agent.mapper.AgentReviewResultMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.MockedConstruction;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 不连接数据库：验证初始化入口在交出数据库前执行回填，失败时释放连接池。 */
class ResultItemStartupTest {
    @BeforeEach
    void initializeMapperMetadataForTheDatabaseTestDouble() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                com.codereview.agent.entity.AgentReviewResult.class);
    }

    @Test
    void databaseBeanRunsBackfillBeforeReturningAndRegistersItemMapper() {
        var results = mock(AgentReviewResultMapper.class);
        when(results.selectList(any())).thenReturn(List.of());
        try (MockedConstruction<AgentDatabase> created = mockConstruction(AgentDatabase.class,
                (db, context) -> when(db.mapper(AgentReviewResultMapper.class)).thenReturn(results))) {
            var db = new AgentDataSourceConfig().agentDatabase(new AgentDataSourceProperties());
            assertSame(created.constructed().get(0), db);
            assertTrue(AgentDataSourceConfig.MAPPERS.contains(AgentReviewResultItemMapper.class));
            verify(results).selectList(any());
            verify(db, never()).destroy();
        }
    }

    @Test
    void backfillFailurePreventsBeanPublicationAndClosesTheDatabase() {
        var results = mock(AgentReviewResultMapper.class);
        when(results.selectList(any())).thenThrow(new IllegalStateException("backfill failed"));
        try (MockedConstruction<AgentDatabase> created = mockConstruction(AgentDatabase.class,
                (db, context) -> when(db.mapper(AgentReviewResultMapper.class)).thenReturn(results))) {
            assertThrows(IllegalStateException.class,
                    () -> new AgentDataSourceConfig().agentDatabase(new AgentDataSourceProperties()));
            verify(created.constructed().get(0)).destroy();
        }
    }

    @Test
    void v9AddsOnlyTheIdentityTableAndUniquenessConstraint() throws Exception {
        try (InputStream stream = getClass().getResourceAsStream("/db/agent-migration/V9__review_result_item.sql")) {
            assertNotNull(stream);
            String sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase().replaceAll("\\s+", " ");
            assertTrue(sql.contains("create table review_result_item"));
            assertTrue(sql.contains("unique key uk_result_item (result_id, kind, ordinal)"));
            assertFalse(sql.contains("alter table"));
            assertFalse(sql.contains("drop table"));
        }
    }
}
