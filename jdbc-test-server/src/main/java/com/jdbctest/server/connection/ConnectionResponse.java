package com.jdbctest.server.connection;

import java.time.Instant;
import java.util.UUID;

public record ConnectionResponse(UUID id, String name, String databaseType, String adapterId,
    String jdbcUrl,
    String username, boolean hasSavedPassword,
    DriverSummaryResponse defaultDriver,
    String description, Instant lastConnectionTestAt,
    ConnectionTestStatus lastConnectionTestStatus,
    Instant createdAt, Instant updatedAt, String executionDatabase,
    String executionSchema) {

}
