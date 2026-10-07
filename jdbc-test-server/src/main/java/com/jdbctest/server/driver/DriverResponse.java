package com.jdbctest.server.driver;

import java.util.List;
import java.util.UUID;

public record DriverResponse(UUID id, String name, String vendor, String version, String driverClass,
                             String urlPrefix, DriverSourceType sourceType, DriverPackageStatus status,
                             String packageFingerprint, String createdAt, String updatedAt,
                             List<DriverArtifactResponse> artifacts) { }
