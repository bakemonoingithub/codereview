package com.codereview.agent.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.codereview.agent.api.ApiException;
import com.codereview.agent.api.PageResponse;
import com.codereview.agent.db.AgentDatabase;
import com.codereview.agent.entity.*;
import com.codereview.agent.execution.TaskProperties;
import com.codereview.agent.invocation.ModelInvocationProperties;
import com.codereview.agent.mapper.*;
import com.codereview.agent.report.ReportGenerationService;
import com.codereview.agent.support.AgentPaging;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SuppressWarnings({"rawtypes", "unchecked"})
class AgentListPaginationTest {
    private AgentDatabase db;
    private final Map<Class<?>, Object> mappers = new HashMap<>();

    @BeforeEach
    void setUp() {
        for (Class<?> entity : List.of(AgentProject.class, AgentModelConfig.class, AgentModelValidation.class,
                AgentPrompt.class, AgentReviewStrategy.class, AgentReviewTask.class,
                AgentTaskTarget.class, AgentReport.class)) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), entity);
        }
        db = mock(AgentDatabase.class);
        when(db.mapper(any())).thenAnswer(call -> mappers.computeIfAbsent(call.getArgument(0), type -> mock(type)));
    }

    @ParameterizedTest
    @CsvSource({"projects,10", "models,20", "prompts,50", "strategies,10", "tasks,20", "reports,50"})
    void sixListsCountTheFilteredRowsAndPreservePagingMetadataAfterMapping(String kind, int size) throws Exception {
        Class<?> entityType = switch (kind) {
            case "projects" -> AgentProject.class;
            case "models" -> AgentModelConfig.class;
            case "prompts" -> AgentPrompt.class;
            case "strategies" -> AgentReviewStrategy.class;
            case "tasks" -> AgentReviewTask.class;
            default -> AgentReport.class;
        };
        Class<?> mapperType = switch (kind) {
            case "projects" -> AgentProjectMapper.class;
            case "models" -> AgentModelConfigMapper.class;
            case "prompts" -> AgentPromptMapper.class;
            case "strategies" -> AgentReviewStrategyMapper.class;
            case "tasks" -> AgentReviewTaskMapper.class;
            default -> AgentReportMapper.class;
        };
        BaseMapper mapper = (BaseMapper) db.mapper(mapperType);
        Object row = entityType.getDeclaredConstructor().newInstance();
        entityType.getMethod("setId", String.class).invoke(row, "row-id");
        entityType.getMethod("setRevision", Integer.class).invoke(row, 0);
        entityType.getMethod("setCreatedAt", LocalDateTime.class).invoke(row, LocalDateTime.of(2026, 10, 22, 0, 0));
        String[] countWhere = new String[1];
        when(mapper.selectCount(any())).thenAnswer(call -> {
            LambdaQueryWrapper<?> query = call.getArgument(0);
            countWhere[0] = query.getSqlSegment();
            assertFalse(countWhere[0].contains("ORDER BY"));
            assertFalse(countWhere[0].contains("limit"));
            if (!kind.equals("reports")) assertTrue(query.getParamNameValuePairs().values().stream()
                    .anyMatch(value -> value.toString().contains("订单")));
            if (kind.equals("tasks")) {
                assertTrue(countWhere[0].contains("project_id"));
                assertTrue(countWhere[0].contains("specialist_type"));
                assertTrue(countWhere[0].contains("state"));
            }
            return 41L;
        });
        when(mapper.selectList(any())).thenAnswer(call -> {
            String sql = ((LambdaQueryWrapper<?>) call.getArgument(0)).getSqlSegment();
            assertTrue(sql.contains(countWhere[0]));
            assertTrue(sql.replaceAll("\\s", "").contains("ORDERBYcreated_atDESC,idDESC"));
            assertTrue(sql.endsWith("limit " + (size < 41 ? size : 0) + ", " + size));
            return List.of(row);
        });
        PageResponse<?> page = switch (kind) {
            case "projects" -> new AgentProjectService(db, null).list("订单", null, null, 2, size);
            case "models" -> new AgentModelService(db, null, new ObjectMapper()).list("订单", null, null, 2, size);
            case "prompts" -> new AgentPromptService(db).list("订单", null, null, 2, size);
            case "strategies" -> new AgentStrategyService(db).list("订单", "ISSUE", null, null, 2, size);
            case "tasks" -> new TaskService(db, new ObjectMapper(), null, null,
                    new ModelInvocationProperties(), new TaskProperties())
                    .list("project-id", "SPECIALIST", "ISSUE", "SUCCEEDED", "订单", null, null, 2, size);
            default -> new ReportGenerationService(db, new ObjectMapper(), null).list(null, null, 2, size);
        };
        assertEquals(41L, page.total());
        assertEquals(size < 41 ? 2 : 1, page.pageNum());
        assertEquals(size, page.pageSize());
        assertEquals(1, page.items().size());
        assertNull(page.nextCursor());
    }

    @Test
    void deletingTheLastPageClampsToTheRemainingLastPageAndZeroRowsClampToOne() {
        AgentProjectMapper mapper = db.mapper(AgentProjectMapper.class);
        when(mapper.selectCount(any())).thenReturn(20L, 0L);
        when(mapper.selectList(any())).thenAnswer(call -> {
            assertTrue(((LambdaQueryWrapper<?>) call.getArgument(0)).getSqlSegment().endsWith("limit 10, 10"));
            return List.of();
        });
        var service = new AgentProjectService(db, null);
        assertEquals(2, service.list(null, null, null, Integer.MAX_VALUE, 10).pageNum());
        var empty = service.list(null, null, null, 5, 10);
        assertEquals(1, empty.pageNum());
        assertEquals(0L, empty.total());
        assertFalse(empty.hasMore());
        verify(mapper, times(1)).selectList(any());
    }

    @ParameterizedTest
    @CsvSource({"0,20", "-1,10", "1,15", "1,100", "1,0"})
    void invalidPageRequestsAreRejectedBeforeQuerying(int page, int size) {
        var service = new AgentProjectService(db, null);
        assertThrows(ApiException.class, () -> service.list(null, null, null, page, size));
        verify(db.mapper(AgentProjectMapper.class), never()).selectCount(any());
    }

    @Test
    void cursorCallersKeepTheirBoundedQueryWithoutCountingAndCannotMixModes() {
        AgentProjectMapper mapper = db.mapper(AgentProjectMapper.class);
        when(mapper.selectList(any())).thenAnswer(call -> {
            assertTrue(((LambdaQueryWrapper<?>) call.getArgument(0)).getSqlSegment().endsWith("limit 21"));
            return List.of();
        });
        var service = new AgentProjectService(db, null);
        PageResponse<?> page = service.list(null, null, 20);
        assertNull(page.total());
        verify(mapper, never()).selectCount(any());
        assertThrows(ApiException.class, () -> service.list(null, "cursor", null, 1, 20));
        assertThrows(ApiException.class, () -> service.list(null, null, 20, 1, 20));
    }
}
