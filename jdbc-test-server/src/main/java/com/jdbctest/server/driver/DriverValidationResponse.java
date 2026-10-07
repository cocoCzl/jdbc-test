package com.jdbctest.server.driver;

public record DriverValidationResponse(DriverPackageStatus status, String driverClass, int majorVersion,
                                       int minorVersion, boolean jdbcCompliant) { }
