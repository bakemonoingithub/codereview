package com.codereview.agent.db;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.function.Supplier;

/**
 * 新库的独立运行单元：自己的连接池、Flyway 链、MyBatis 工厂与事务模板。
 *
 * <p>刻意<b>不</b>注册 {@code DataSource} 类型的 Bean：旧库仍由 Spring Boot 自动配置创建，
 * 一旦多出一个 DataSource Bean，旧库的自动配置会整体退让。新库只在
 * {@link #mapper(Class)} 与 {@link #inTransaction(Supplier)} 内部可见。
 *
 * <p>调用顺序固定：建池 → 身份检查 → 迁移 → 标记校验 → 建 MyBatis。任一步失败直接抛出，
 * 不带着半可用的新库继续启动。
 */
public class AgentDatabase implements DisposableBean {

    private final AgentDataSourceProperties props;
    private final List<Class<?>> mappers;
    private HikariDataSource pool;
    private SqlSessionTemplate template;
    private TransactionTemplate transactionTemplate;
    private AgentSchemaGuard guard;

    public AgentDatabase(AgentDataSourceProperties props, List<Class<?>> mappers) {
        this.props = props;
        this.mappers = mappers;
        start();
    }

    private void start() {
        if (props.getUrl() == null || props.getUrl().isBlank()) {
            throw new AgentSchemaMismatchException("未配置 agent.datasource.url，无法初始化新库");
        }
        this.guard = new AgentSchemaGuard(props.getExpectedDatabase(), props.getHistoryTable());
        this.pool = newPool();
        guard.preMigrate(pool);
        Flyway flyway = Flyway.configure()
                .dataSource(pool)
                .locations(props.getMigrationLocation())
                .table(props.getHistoryTable())
                .baselineOnMigrate(false)
                .validateMigrationNaming(true)
                .load();
        flyway.migrate();
        guard.postMigrate(pool);
        this.template = new SqlSessionTemplate(newFactory());
        this.transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(pool));
    }

    private HikariDataSource newPool() {
        HikariConfig config = new HikariConfig();
        config.setPoolName("agent-db");
        config.setJdbcUrl(props.getUrl());
        config.setUsername(props.getUsername());
        config.setPassword(props.getPassword());
        if (props.getDriverClassName() != null && !props.getDriverClassName().isBlank()) {
            config.setDriverClassName(props.getDriverClassName());
        }
        config.setMaximumPoolSize(props.getMaximumPoolSize());
        config.setMinimumIdle(1);
        config.setConnectionTimeout(10_000);
        return new HikariDataSource(config);
    }

    private SqlSessionFactory newFactory() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setEnvironment(new Environment(
                "agent", new SpringManagedTransactionFactory(), pool));
        configuration.setMapUnderscoreToCamelCase(true);
        GlobalConfig global = new GlobalConfig();
        global.setBanner(false);
        global.setDbConfig(new GlobalConfig.DbConfig()
                .setLogicDeleteField("isDeleted")
                .setLogicDeleteValue("1")
                .setLogicNotDeleteValue("0"));
        GlobalConfigUtils.setGlobalConfig(configuration, global);
        for (Class<?> mapper : mappers) {
            configuration.addMapper(mapper);
        }
        return new MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    /** 取 MyBatis 映射器；每次调用都通过当前线程的会话取代理，不做跨请求缓存。 */
    public <T> T mapper(Class<T> mapperType) {
        return template.getMapper(mapperType);
    }

    /** 在新库事务内执行；抛异常整体回滚。 */
    public <T> T inTransaction(Supplier<T> work) {
        return transactionTemplate.execute(status -> work.get());
    }

    /** 只读健康检查用：库名与版本标记。 */
    public String schemaVersion() {
        guard.postMigrate(pool);
        return AgentSchemaGuard.EXPECTED_VERSION;
    }

    @Override
    public void destroy() {
        if (pool != null) {
            pool.close();
        }
    }
}
