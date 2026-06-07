package com.jdbctest.config;

import java.util.List;
import java.util.Locale;

public class Config {

    public enum DbType {
        POSTGRESQL, GAUSSDB, MYSQL, ORACLE, SQLSERVER, UNKNOWN;

        public static DbType from(String s) {
            if (s == null) return UNKNOWN;
            return switch (s.toLowerCase(Locale.ENGLISH)) {
                case "postgresql" -> POSTGRESQL;
                case "gaussdb" -> GAUSSDB;
                case "mysql" -> MYSQL;
                case "oracle" -> ORACLE;
                case "sqlserver" -> SQLSERVER;
                default -> UNKNOWN;
            };
        }

        public String getIdentifierQuote() {
            return switch (this) {
                case MYSQL -> "`";
                case POSTGRESQL, GAUSSDB, ORACLE, SQLSERVER -> "\"";
                default -> "\"";
            };
        }
    }

    public DbConfig db;
    public DdlConfig ddl;
    public DmlConfig dml;
    public PoolConfig pool;
    public ProfileConfig profile;
    public ConcurrencyConfig concurrency;
    public ExecutionConfig execution;
    public ReportConfig report;
    public TestFilterConfig testFilter;

    public static class DbConfig {
        public DbType type = DbType.UNKNOWN;
        public String username;
        public String password;
        public String url;

        public String getJdbcUrl() {
            if (url != null && !url.isBlank()) {
                return url;
            }
            throw new IllegalStateException("db.url 不能为空，必须配置完整 JDBC URL");
        }

        public String getDriverClass() {
            return switch (type) {
                case POSTGRESQL, GAUSSDB -> "org.postgresql.Driver";
                case MYSQL -> "com.mysql.cj.jdbc.Driver";
                case ORACLE -> "oracle.jdbc.OracleDriver";
                case SQLSERVER -> "com.microsoft.sqlserver.jdbc.SQLServerDriver";
                default -> "";
            };
        }

    }

    public static class DdlConfig {
        public String basePath;
    }

    public static class DmlConfig {
        public String basePath;
    }

    public static class PoolConfig {
        public String profileDir;
    }

    public static class ProfileConfig {
        public String profileDir;
    }

    public static class ConcurrencyConfig {
        public boolean enabled;
        public int threads;
        public long timeout;
    }

    public static class ExecutionConfig {
        public String mode;
    }

    public static class ReportConfig {
        public String outputDir;
        public List<String> format;
    }

    public static class TestFilterConfig {
        public List<String> includeTests;
        public List<String> excludeTests;
        public long timeout;
    }
}
