package com.codereview.agent.db;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P30.5／P30.6 增量迁移的契约测试。
 *
 * <p>不连数据库；锁住的是公共调用与任务控制依赖的结构：调用可脱离任务、
 * 额度表存在、幂等键唯一。迁移语法仍须在隔离新库实际执行（P32.6）。
 */
class AgentFoundationMigrationContractTest {

    private static String sql(String name) throws IOException {
        try (InputStream in = AgentFoundationMigrationContractTest.class
                .getResourceAsStream("/db/agent-migration/" + name)) {
            assertNotNull(in, "找不到迁移 " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .toLowerCase().replaceAll("\\s+", " ");
        }
    }

    @Test
    void invocationMigrationAllowsStandaloneCallsAndAddsQuota() throws IOException {
        String sql = sql("V2__agent_execution.sql");

        assertTrue(sql.contains("alter table model_call"));
        assertTrue(sql.contains("modify column task_id char(26) null"));
        assertTrue(sql.contains("create table task_quota"));
        assertTrue(sql.contains("reserved int not null default 0"));
        assertTrue(sql.contains("primary key (task_id)"));
    }

    @Test
    void taskMigrationAddsAUniqueIdempotencyKey() throws IOException {
        String sql = sql("V3__agent_task.sql");

        assertTrue(sql.contains("idempotency_key varchar(100) null"));
        assertTrue(sql.contains("request_hash char(64) null"));
        assertTrue(sql.contains("create unique index uk_task_idempotency"));
        assertTrue(sql.contains("(project_id, idempotency_key)"));
    }

    @Test
    void reportGenerationMigrationLinksCallsAndAddsTimestamps() throws IOException {
        String sql = sql("V4__agent_report_generation.sql");

        assertTrue(sql.contains("alter table model_call"));
        assertTrue(sql.contains("add column report_id char(26) null"));
        assertTrue(sql.contains("create index") || sql.contains("add key idx_model_call_report"));
        assertTrue(sql.contains("alter table report"));
        assertTrue(sql.contains("add column started_at"));
        assertTrue(sql.contains("add column finished_at"));
    }

    @Test
    void strategyVersionMigrationPreservesLegacyNullReferences() throws IOException {
        String sql = sql("V7__strategy_prompt_version.sql");
        assertTrue(sql.contains("alter table review_strategy"));
        assertTrue(sql.contains("add column prompt_version_id char(26) null"));
        org.junit.jupiter.api.Assertions.assertFalse(sql.contains("update review_strategy"));
        org.junit.jupiter.api.Assertions.assertFalse(sql.contains("drop "));
    }

    @Test
    void schemaIdentityStaysOnTheSameGenerationForAdditiveMigrations() {
        assertTrue(AgentSchemaGuard.EXPECTED_VERSION.matches("\\d+"));
    }
}
