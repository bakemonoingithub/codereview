package com.codereview.agent.db;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 新链迁移的契约测试。
 *
 * <p>后端测试不连数据库，V1 的语法与表结构不会被任何用例执行；这里锁住的是
 * "新链必须完整、必须独立、必须能承载 P29 设计字段"这三件改了就出事的事。
 */
class AgentMigrationContractTest {

    private static String sql() throws IOException {
        try (InputStream in = AgentMigrationContractTest.class.getResourceAsStream("/db/agent-migration/V1__agent_init.sql")) {
            assertNotNull(in, "找不到新链 V1");
            String raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return raw.toLowerCase().replaceAll("\\s+", " ");
        }
    }

    @Test
    void createsEveryTableNamedInTheP29Design() throws IOException {
        String sql = sql().toLowerCase();
        List<String> tables = List.of(
                "agent_schema_identity", "project", "model_config", "model_validation",
                "prompt", "prompt_version", "review_strategy", "review_task", "task_target",
                "execution_unit", "model_call", "material", "call_material", "review_result",
                "issue", "feedback", "report", "report_source");
        for (String table : tables) {
            assertTrue(sql.contains("create table " + table + " ("), "缺少表 " + table);
        }
        assertEquals(18, count(sql, "engine = innodb"), "所有表都应显式声明 InnoDB");
    }

    @Test
    void neverCarriesLegacyTables() throws IOException {
        String sql = sql().toLowerCase();
        for (String legacy : List.of("review_record", "report_record", "issue_mark", "workflow_run",
                "workflow_call", "finding_assessment")) {
            assertFalse(sql.contains("create table " + legacy + " ("), "新库不应包含旧表 " + legacy);
        }
    }

    @Test
    void keepsUnboundedLlmPayloadsInMediumtext() throws IOException {
        String sql = sql().toLowerCase();
        assertTrue(sql.contains("frozen_input         longtext") || sql.contains("frozen_input longtext"));
        for (String column : List.of("request_body", "response_body", "body", "content", "source_snapshot")) {
            assertTrue(sql.contains(column + " "), "缺少列 " + column);
        }
        assertTrue(sql.contains("report_source") && sql.contains("source_snapshot longtext"));
    }

    @Test
    void doesNotRelyOnCheckConstraintsOrForeignKeys() throws IOException {
        String sql = sql().toLowerCase();
        assertFalse(sql.contains("check ("), "MySQL 版本未必强制执行 CHECK，不能只靠它保证业务约束");
        assertFalse(sql.contains("foreign key"), "沿用逻辑外键，不建物理外键");
    }

    @Test
    void insertsTheSchemaIdentityMarker() throws IOException {
        String sql = sql().toLowerCase();
        assertTrue(sql.contains("insert into agent_schema_identity"));
        assertTrue(sql.contains("'ai_code_review_agent'"));
        assertTrue(sql.contains("'1'"));
    }

    private static int count(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
