package com.jdbctest.server.connection;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record CreateConnectionRequest(
    @NotBlank @Size(max = 200) String name,
    @NotBlank @Size(max = 80) String databaseType,
    @NotBlank @Size(max = 100) String adapterId,
    @NotBlank @Size(max = 2000) String jdbcUrl,
    @Size(max = 200) String username,
    @Size(max = 2000) String password,
    @NotNull Boolean savePassword,
    @NotNull UUID defaultDriverId,
    @Size(max = 1000) String description,
    @Size(max = 63) String executionDatabase,
    @Size(max = 63) String executionSchema,
    @Size(max = 20) String sqlDialect) {

    public CreateConnectionRequest(String name, String databaseType, String adapterId,
        String jdbcUrl, String username, String password, Boolean savePassword, UUID defaultDriverId,
        String description, String executionDatabase, String executionSchema) {
        this(name, databaseType, adapterId, jdbcUrl, username, password, savePassword,
            defaultDriverId, description, executionDatabase, executionSchema, null);
    }

}
