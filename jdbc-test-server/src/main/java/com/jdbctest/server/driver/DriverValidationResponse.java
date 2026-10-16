package com.jdbctest.server.driver;

import java.util.List;

public record DriverValidationResponse(DriverPackageStatus status, String driverClass,
    int majorVersion,
    int minorVersion, boolean jdbcCompliant, List<String> warnings) {

    public DriverValidationResponse {
        warnings = List.copyOf(warnings);
    }
}
