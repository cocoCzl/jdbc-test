package com.jdbctest.server.driver;

import java.util.List;

public record DriverPageResponse(List<DriverResponse> items, int page, int size, long totalElements, int totalPages) { }
