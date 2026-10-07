package com.jdbctest.server.driver;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateDriverRequest(
        @NotBlank @Size(max = 200) String name,
        @NotBlank @Size(max = 120) String vendor,
        @NotBlank @Size(max = 80) String version,
        @NotBlank @Size(max = 300) String driverClass,
        @Size(max = 200) String urlPrefix) { }
