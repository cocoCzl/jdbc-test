package com.jdbctest.server.connection;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record UpdateConnectionRequest(
        @NotBlank @Size(max = 200) String name,
        @NotBlank @Size(max = 80) String databaseType,
        @NotBlank @Size(max = 100) String adapterId,
        @NotBlank @Size(max = 2000) String jdbcUrl,
        @Size(max = 200) String username,
        @Size(max = 2000) String password,
        Boolean savePassword,
        Boolean clearSavedPassword,
        @NotNull UUID defaultDriverId,
        @Size(max = 1000) String description) { }
