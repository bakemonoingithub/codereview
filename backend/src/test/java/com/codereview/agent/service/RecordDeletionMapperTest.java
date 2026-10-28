package com.codereview.agent.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.codereview.agent.mapper.AgentReportMapper;
import com.codereview.agent.mapper.AgentReviewTaskMapper;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** 生成正式 Mapper SQL，完全不建立数据库连接。 */
class RecordDeletionMapperTest {
    @Test void rootReadsFilterDeletedRowsAndWritesGuardStateAndRevision() {
        var config = new MybatisConfiguration();
        config.addMapper(AgentReviewTaskMapper.class);
        config.addMapper(AgentReportMapper.class);
        new MybatisSqlSessionFactoryBuilder().build(config);
        for (Class<?> mapper : new Class<?>[]{AgentReviewTaskMapper.class, AgentReportMapper.class}) {
            String prefix = mapper.getName() + ".";
            String read = config.getMappedStatement(prefix + "selectById")
                    .getBoundSql(Map.of("id", "id")).getSql();
            assertTrue(read.contains("is_deleted=0"), read);
            String update = config.getMappedStatement(prefix + "markDeleted")
                    .getBoundSql(Map.of("id", "id", "revision", 1)).getSql();
            assertTrue(update.contains("is_deleted=0"));
            assertTrue(update.contains("revision=?"));
            assertTrue(update.contains("state IN"));
            String lock = config.getMappedStatement(prefix + "selectIncludingDeletedForUpdate")
                    .getBoundSql(Map.of("id", "id")).getSql();
            assertTrue(lock.endsWith("FOR UPDATE"));
        }
    }
}
