package com.jdbctest.server.connection;

import java.util.List;

public record ConnectionPageResponse(List<ConnectionResponse> items, int page, int size,
    long totalElements, int totalPages) {

}
