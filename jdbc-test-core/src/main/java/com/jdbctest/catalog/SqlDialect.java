package com.jdbctest.catalog;

import java.util.Locale;

/**
 * Infrastructure differences only; never changes driver responses or arbitrary SQL.
 */
public enum SqlDialect {
    POSTGRESQL("postgresql"),
    MYSQL("mysql"),
    ORACLE("oracle"),
    DM("dm"),
    SQL_SERVER("sqlserver"),
    GENERIC("generic");

    private final String code;

    SqlDialect(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static SqlDialect fromCode(String code) {
        return switch (code == null ? "" : code) {
            case "postgresql" -> POSTGRESQL;
            case "mysql" -> MYSQL;
            case "oracle" -> ORACLE;
            case "dm" -> DM;
            case "sqlserver" -> SQL_SERVER;
            default -> throw new IllegalArgumentException("ADAPTER_DIALECT_INVALID");
        };
    }

    /**
     * Retain the old unconfigured Suite behavior; manifests must use an implemented dialect.
     */
    public static SqlDialect fromLegacyConfig(String code) {
        for (SqlDialect dialect : values()) {
            if (dialect.code.equalsIgnoreCase(code)) {
                return dialect;
            }
        }
        return GENERIC;
    }

    public String selection() {
        return this == MYSQL ? "catalog" : "schema";
    }

    public boolean requiresDatabase() {
        return this != ORACLE && this != DM;
    }

    public String normalizeIdentifier(String value) {
        return this == ORACLE ? value.toUpperCase(Locale.ROOT) : value;
    }

    public String quoteIdentifier(String value, String quote) {
        String end = "[".equals(quote) ? "]" : quote;
        return quote + value.replace(end, end + end) + end;
    }

    public boolean indexDropRequiresTable() {
        return this == MYSQL || this == SQL_SERVER;
    }
}
