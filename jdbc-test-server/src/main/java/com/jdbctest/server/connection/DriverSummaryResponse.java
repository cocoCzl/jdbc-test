package com.jdbctest.server.connection;

import com.jdbctest.server.driver.DriverPackageStatus;
import java.util.UUID;

public record DriverSummaryResponse(UUID id, String name, String vendor, String version, DriverPackageStatus status) { }
