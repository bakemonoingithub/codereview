package com.codereview.agent.db;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.codereview.agent.entity.PrAutoReviewConfig;
import com.codereview.agent.entity.PrAutoReviewDelivery;
import com.codereview.agent.mapper.PrAutoReviewConfigMapper;
import com.codereview.agent.mapper.PrAutoReviewDeliveryMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PRA-02 数据库迁移 V11 契约测试。
 * 验证 pr_auto_review_config 与 pr_auto_review_delivery 表结构、字段、索引与 InnoDB/utf8mb4 规范。
 */
class PrAutoReviewMigrationContractTest {

    private static String sql() throws IOException {
        try (InputStream in = PrAutoReviewMigrationContractTest.class
                .getResourceAsStream("/db/agent-migration/V11__pr_auto_review.sql")) {
            assertNotNull(in, "找不到新链 V11");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .toLowerCase().replaceAll("\\s+", " ");
        }
    }

    @Test
    @DisplayName("V11 迁移创建配置表与投递处理记录表，均显式使用 InnoDB 与 utf8mb4")
    void createsConfigAndDeliveryTables() throws IOException {
        String sql = sql();
        assertTrue(sql.contains("create table pr_auto_review_config ("), "缺少 pr_auto_review_config 表");
        assertTrue(sql.contains("create table pr_auto_review_delivery ("), "缺少 pr_auto_review_delivery 表");
        assertEquals(2, count(sql, "engine = innodb default charset = utf8mb4"));
    }

    @Test
    @DisplayName("V11 迁移包含去重索引与项目业务幂等键唯一索引")
    void carriesRequiredConstraintsAndIndexes() throws IOException {
        String sql = sql();
        assertTrue(sql.contains("unique key uk_pra_config_project (project_id)"));
        assertTrue(sql.contains("unique key uk_delivery (host_type, host_instance_key, delivery_id)"));
        assertTrue(sql.contains("unique key uk_project_business_key (project_id, business_key)"));
        assertTrue(sql.contains("idx_project_received (project_id, first_received_at, id)"));
        assertTrue(sql.contains("idx_host_repo_pr (host_type, host_instance_key, repo_id, pr_number, first_received_at, id)"));
        assertTrue(sql.contains("idx_review_task_id (review_task_id)"));
        assertTrue(sql.contains("idx_canonical_record (canonical_record_id)"));
        assertTrue(sql.contains("idx_next_attempt (next_attempt_at, id)"));
        assertFalse(sql.contains("foreign key"), "新库不使用物理外键");
        assertFalse(sql.contains("check ("), "新库不依赖 CHECK 约束");
    }

    @Test
    void replaySqlIncrementsOnlyReceiveFields() {
        MybatisConfiguration mybatis = new MybatisConfiguration();
        mybatis.addMapper(PrAutoReviewDeliveryMapper.class);
        String sql = mybatis.getMappedStatement(PrAutoReviewDeliveryMapper.class.getName() + ".upsertReceived")
                .getBoundSql(new PrAutoReviewDelivery()).getSql().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();

        String[] clauses = sql.split(" on duplicate key update ");
        assertEquals(2, clauses.length);
        assertEquals("receive_count = receive_count + 1, last_received_at = greatest(last_received_at, ?)", clauses[1]);
    }

    @Test
    void nullablePromptReferencesAreWrittenInConfigUpdate() {
        MybatisConfiguration mybatis = new MybatisConfiguration();
        mybatis.addMapper(PrAutoReviewConfigMapper.class);
        PrAutoReviewConfig config = new PrAutoReviewConfig();
        config.setId("existing-config");
        String sql = mybatis.getMappedStatement(PrAutoReviewConfigMapper.class.getName() + ".updateById")
                .getBoundSql(Map.of("et", config)).getSql();

        assertTrue(sql.contains("prompt_id"));
        assertTrue(sql.contains("prompt_version_id"));
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
