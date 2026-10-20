package com.jdbctest.server.driver;

import java.util.List;

public record DriverValidationResponse(DriverPackageStatus status, String driverClass,
    int majorVersion,
    int minorVersion, boolean jdbcCompliant, List<String> warnings, LoadedArtifact loadedArtifact) {

    public DriverValidationResponse {
        warnings = List.copyOf(warnings);
    }

    public record LoadedArtifact(String fileName, String sha256, String implementationVersion,
        String bundleVersion) {
    }
}
