package com.jdbctest.server.driver;

import java.util.List;

/**
 * Form suggestions only, independent of adapter declarations and runtime driver configuration.
 */
public record DriverPreset(String code, String nameKey, String vendor, String driverClass,
    String urlPrefix) {

    private static final List<DriverPreset> DEFAULTS = List.of(
        new DriverPreset("postgresql", "driverPreset.postgresql", "PostgreSQL",
            "org.postgresql.Driver", "jdbc:postgresql:"),
        new DriverPreset("mysql", "driverPreset.mysql", "MySQL", "com.mysql.cj.jdbc.Driver",
            "jdbc:mysql:"),
        new DriverPreset("oracle", "driverPreset.oracle", "Oracle", "oracle.jdbc.OracleDriver",
            "jdbc:oracle:"),
        new DriverPreset("sqlserver", "driverPreset.sqlserver", "Microsoft",
            "com.microsoft.sqlserver.jdbc.SQLServerDriver", "jdbc:sqlserver:"),
        new DriverPreset("kingbase", "driverPreset.kingbase", "Kingbase", "com.kingbase8.Driver",
            "jdbc:kingbase8:"),
        new DriverPreset("custom", "driverPreset.custom", "", "", ""));

    static List<DriverPreset> defaults() {
        return DEFAULTS;
    }
}
