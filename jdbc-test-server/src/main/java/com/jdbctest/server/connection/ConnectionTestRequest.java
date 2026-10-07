package com.jdbctest.server.connection;

import jakarta.validation.constraints.Size;

public record ConnectionTestRequest(@Size(max = 2000) String password) { }
