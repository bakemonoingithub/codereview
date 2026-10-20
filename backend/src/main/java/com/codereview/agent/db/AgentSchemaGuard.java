package com.codereview.agent.db;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 新库身份检查。
 *
 * <p>三道门，全部在启动期执行：
 * <ol>
 *   <li>连接实际落到的库名必须是配置的 {@code expected-database} —— 防止把新链迁到旧库；</li>
 *   <li>该库不得存在旧链的 {@code flyway_schema_history}；</li>
 *   <li>迁移完成后 {@code agent_schema_identity} 必须存在且版本匹配，历史表与标记不能只出现一半。</li>
 * </ol>
 *
 * <p>纯规则抽成静态方法，便于在不连库的单元测试里逐条覆盖；SQL 部分只负责取值。
 */
public class AgentSchemaGuard {

    public static final String EXPECTED_SCHEMA = "ai_code_review_agent";
    public static final String EXPECTED_VERSION = "1";
    public static final String IDENTITY_TABLE = "agent_schema_identity";
    public static final String LEGACY_HISTORY_TABLE = "flyway_schema_history";

    private final String expectedDatabase;
    private final String historyTable;

    public AgentSchemaGuard(String expectedDatabase, String historyTable) {
        this.expectedDatabase = expectedDatabase;
        this.historyTable = historyTable;
    }

    public void preMigrate(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            String catalog = connection.getCatalog();
            requireDatabaseName(expectedDatabase, catalog);
            boolean legacyHistory = tableExists(connection, catalog, LEGACY_HISTORY_TABLE);
            boolean ownHistory = tableExists(connection, catalog, historyTable);
            boolean identity = tableExists(connection, catalog, IDENTITY_TABLE);
            requireNotLegacy(legacyHistory, catalog);
            requireConsistentHistory(ownHistory, identity, catalog);
        } catch (SQLException e) {
            throw new AgentSchemaMismatchException("检查新库身份失败：" + e.getMessage());
        }
    }

    public void postMigrate(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            String catalog = connection.getCatalog();
            String version = schemaVersion(connection, catalog);
            requireSchemaVersion(EXPECTED_VERSION, version);
        } catch (SQLException e) {
            throw new AgentSchemaMismatchException("读取新库身份标记失败：" + e.getMessage());
        }
    }

    public static void requireDatabaseName(String expected, String actual) {
        if (actual == null || actual.isBlank()) {
            throw new AgentSchemaMismatchException("连接上没有库名，无法确认指向新库；请在 URL 中指定数据库");
        }
        if (expected != null && !expected.isBlank() && !expected.equalsIgnoreCase(actual)) {
            throw new AgentSchemaMismatchException(
                    "新库配置指向了 " + actual + "，与期望的 " + expected + " 不一致；拒绝迁移");
        }
    }

    public static void requireNotLegacy(boolean legacyHistoryExists, String catalog) {
        if (legacyHistoryExists) {
            throw new AgentSchemaMismatchException(
                    "库 " + catalog + " 已存在旧链 flyway_schema_history；新链必须使用独立的新库");
        }
    }

    public static void requireConsistentHistory(boolean ownHistory, boolean identity, String catalog) {
        if (ownHistory != identity) {
            throw new AgentSchemaMismatchException(
                    "库 " + catalog + " 的 " + IDENTITY_TABLE + " 与 " + "agent_schema_history"
                            + " 只存在其一；请人工确认后再启动");
        }
    }

    public static void requireSchemaVersion(String expected, String actual) {
        if (actual == null) {
            throw new AgentSchemaMismatchException("缺少 " + IDENTITY_TABLE + " 标记，拒绝启动");
        }
        if (!expected.equals(actual)) {
            throw new AgentSchemaMismatchException(
                    "新库版本为 " + actual + "，当前程序要求 " + expected + "；请先升级数据库");
        }
    }

    private static boolean tableExists(Connection connection, String catalog, String table) throws SQLException {
        String sql = "select count(*) from information_schema.tables where table_schema = ? and table_name = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, catalog);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }

    private static String schemaVersion(Connection connection, String catalog) throws SQLException {
        if (!tableExists(connection, catalog, IDENTITY_TABLE)) {
            return null;
        }
        String sql = "select schema_version from " + IDENTITY_TABLE + " where schema_name = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, EXPECTED_SCHEMA);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }
}
