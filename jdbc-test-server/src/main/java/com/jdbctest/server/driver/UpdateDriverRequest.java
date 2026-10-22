package com.jdbctest.server.driver;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateDriverRequest(
    @NotBlank @Size(max = 200) String name,
    @NotBlank @Size(max = 120) String vendor,
    @NotBlank @Size(max = 80) String version,
    @NotBlank @Size(max = 300) String driverClass,
    @Size(max = 200) String urlPrefix, com.jdbctest.catalog.DataSourceProviders dataSources,
    @Size(max = 20) String defaultSqlDialect) {

    public UpdateDriverRequest(String name, String vendor, String version, String driverClass,
        String urlPrefix, com.jdbctest.catalog.DataSourceProviders dataSources) {
        this(name, vendor, version, driverClass, urlPrefix, dataSources, null);
    }

    public UpdateDriverRequest(String name, String vendor, String version, String driverClass,
        String urlPrefix) {
        this(name, vendor, version, driverClass, urlPrefix, null);
    }
}
