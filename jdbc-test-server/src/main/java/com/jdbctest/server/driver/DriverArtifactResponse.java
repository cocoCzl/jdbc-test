package com.jdbctest.server.driver;

import java.util.UUID;

public record DriverArtifactResponse(UUID id, String fileName, long size, String sha256) { }
