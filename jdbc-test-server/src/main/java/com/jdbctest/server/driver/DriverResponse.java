package com.jdbctest.server.driver;

import java.util.List;
import java.util.UUID;

public record DriverResponse(UUID id, String name, String vendor, String version,
    String driverClass,
    String urlPrefix, DriverSourceType sourceType,
    DriverPackageStatus status,
    String packageFingerprint, String createdAt, String updatedAt,
    List<DriverArtifactResponse> artifacts, com.jdbctest.catalog.DataSourceProviders dataSources,
    String defaultSqlDialect) {

    public DriverResponse(UUID id, String name, String vendor, String version, String driverClass,
        String urlPrefix, DriverSourceType sourceType, DriverPackageStatus status,
        String packageFingerprint, String createdAt, String updatedAt,
        List<DriverArtifactResponse> artifacts,
        com.jdbctest.catalog.DataSourceProviders dataSources) {
        this(id, name, vendor, version, driverClass, urlPrefix, sourceType, status,
            packageFingerprint, createdAt, updatedAt, artifacts, dataSources, null);
    }

}
