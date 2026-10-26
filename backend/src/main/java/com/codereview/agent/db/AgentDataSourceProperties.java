package com.codereview.agent.db;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Required configuration for the application's sole database. */
@ConfigurationProperties(prefix = "agent.datasource")
public class AgentDataSourceProperties {

    private String url;
    private String username;
    private String password;
    private String driverClassName = "com.mysql.cj.jdbc.Driver";

    /** 期望的库名；连接实际指向别的库时拒绝迁移，避免误连旧库。 */
    private String expectedDatabase = "ai_code_review_agent";

    /** 新链迁移位置，独立于旧库的 classpath:db/migration。 */
    private String migrationLocation = "classpath:db/agent-migration";

    /** 新链的 Flyway 历史表；与旧库的 flyway_schema_history 区分，后者出现即视为误连旧库。 */
    private String historyTable = "agent_schema_history";

    private int maximumPoolSize = 10;
    private int minimumIdle = 1;
    private long connectionTimeoutMillis = 10000;
    public int getMinimumIdle() { return minimumIdle; }
    public void setMinimumIdle(int value) { minimumIdle = value; }
    public long getConnectionTimeoutMillis() { return connectionTimeoutMillis; }
    public void setConnectionTimeoutMillis(long value) { connectionTimeoutMillis = value; }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getDriverClassName() {
        return driverClassName;
    }

    public void setDriverClassName(String driverClassName) {
        this.driverClassName = driverClassName;
    }

    public String getExpectedDatabase() {
        return expectedDatabase;
    }

    public void setExpectedDatabase(String expectedDatabase) {
        this.expectedDatabase = expectedDatabase;
    }

    public String getMigrationLocation() {
        return migrationLocation;
    }

    public void setMigrationLocation(String migrationLocation) {
        this.migrationLocation = migrationLocation;
    }

    public String getHistoryTable() {
        return historyTable;
    }

    public void setHistoryTable(String historyTable) {
        this.historyTable = historyTable;
    }

    public int getMaximumPoolSize() {
        return maximumPoolSize;
    }

    public void setMaximumPoolSize(int maximumPoolSize) {
        this.maximumPoolSize = maximumPoolSize;
    }
}
