package com.jdbctest.config;

import java.util.List;
import java.util.Map;

public class Config {

    public DbConfig db;
    public DdlConfig ddl;
    public DmlConfig dml;
    public PoolConfig pool;
    public NamespaceConfig namespace;
    public AdapterConfig adapter;
    public PreflightConfig preflight;
    public ExecutionConfig execution;
    public ReportConfig report;
    public TestFilterConfig testFilter;

    public static class DbConfig {
        public String type = "unknown";
        public String adapterId = "unknown";
        public String assetId = "unknown";
        public String dialect = "generic";
        public String username;
        public String password;
        public String url;
        public String driverClass;
        public String connectionMode = "hikari";
        public Map<String, String> properties = Map.of();
        public String identifierQuote = "\"";
        public String expectedDatabaseProductRegex;
        public String expectedDriverNameRegex;
        public String databaseVersionMin;
        public String databaseVersionMax;
        public String driverVersionMin;
        public String driverVersionMax;

        public String getJdbcUrl() {
            if (url != null && !url.isBlank()) {
                return url;
            }
            throw new IllegalStateException("db.url 不能为空，必须配置完整 JDBC URL");
        }

        public String getDriverClass() {
            return driverClass == null ? "" : driverClass;
        }

        public boolean isDriverManagerMode() {
            return "driver_manager".equalsIgnoreCase(connectionMode);
        }

        public String getIdentifierQuote() {
            return identifierQuote == null || identifierQuote.isEmpty() ? "\"" : identifierQuote;
        }

        public boolean isDialect(String expected) {
            return expected != null && expected.equalsIgnoreCase(dialect);
        }
    }

    public static class NamespaceConfig {
        public String mode = "existing";
        public String name;
        public String selection = "none";
        public String createSql;
        public String dropSql;
        public String selectSql;
        public boolean dropOnExit;
        public boolean destructiveConsent;
    }

    public static class AdapterConfig {
        public String id;
        public String trust;
        public Map<String, Boolean> capabilities = Map.of();
    }

    public static class PreflightConfig {
        public String namespaceCreatePrivilege;
        public String probeName;
        public List<PrivilegeCheck> privilegeChecks = List.of();
    }

    public static class PrivilegeCheck {
        public String privilege;
        public String sql;
        public String cleanupSql;
    }

    public static class DdlConfig { public String basePath; }
    public static class DmlConfig { public String basePath; }
    public static class PoolConfig { public String profileDir; }
    public static class ExecutionConfig { public String mode; }
    public static class ReportConfig { public String outputDir; public List<String> format; }
    public static class TestFilterConfig {
        public List<String> includeTests;
        public List<String> excludeTests;
        public long timeoutMs;
    }
}
