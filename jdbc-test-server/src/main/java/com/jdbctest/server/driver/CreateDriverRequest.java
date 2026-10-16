package com.jdbctest.server.driver;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateDriverRequest(
    @NotBlank @Size(max = 200) String name,
    @NotBlank @Size(max = 120) String vendor,
    @NotBlank @Size(max = 80) String version,
    @Size(max = 300) String driverClass,
    @Size(max = 200) String urlPrefix, com.jdbctest.catalog.DataSourceProviders dataSources) {

    public CreateDriverRequest(String name, String vendor, String version, String driverClass,
        String urlPrefix) {
        this(name, vendor, version, driverClass, urlPrefix, null);
    }
}
