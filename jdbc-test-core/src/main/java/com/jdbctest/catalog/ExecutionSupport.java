package com.jdbctest.catalog;

import java.util.Locale;

/** Execution policy only. Historical enum values and raw evidence remain readable. */
public final class ExecutionSupport {

    private ExecutionSupport() {
    }

    public static void requireActive(String... values) {
        for (String value : values) {
            String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
            if (normalized.equals("sqlserver") || normalized.equals("sql_server")
                || normalized.equals("microsoft sql server") || normalized.equals("sql server") || normalized.startsWith("jdbc:sqlserver:")
                || normalized.startsWith("jdbc:jtds:sqlserver:")
                || normalized.startsWith("com.microsoft.sqlserver.")
                || normalized.equals("net.sourceforge.jtds.jdbc.driver")) {
                throw new IllegalArgumentException(
                    "DATABASE_SUPPORT_RETIRED: SQL Server execution is no longer supported");
            }
        }
    }
}
