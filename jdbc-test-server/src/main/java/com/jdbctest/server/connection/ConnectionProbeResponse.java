package com.jdbctest.server.connection;

public record ConnectionProbeResponse(boolean success, String databaseProductName,
    String databaseProductVersion,
    String driverName, String driverVersion,
    Integer jdbcMajorVersion, Integer jdbcMinorVersion) {

}
