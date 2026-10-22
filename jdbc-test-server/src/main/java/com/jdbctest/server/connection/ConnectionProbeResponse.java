package com.jdbctest.server.connection;

public record ConnectionProbeResponse(boolean success, String databaseProductName,
    String databaseProductVersion,
    String driverName, String driverVersion,
    Integer jdbcMajorVersion, Integer jdbcMinorVersion, String currentCatalog, String currentSchema,
    String targetContainer) {

    public ConnectionProbeResponse(boolean success, String databaseProductName,
        String databaseProductVersion, String driverName, String driverVersion,
        Integer jdbcMajorVersion, Integer jdbcMinorVersion) {
        this(success, databaseProductName, databaseProductVersion, driverName, driverVersion,
            jdbcMajorVersion, jdbcMinorVersion, null, null, null);
    }

}
