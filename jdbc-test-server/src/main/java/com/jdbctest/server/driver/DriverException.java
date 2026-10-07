package com.jdbctest.server.driver;

import java.util.Map;
import org.springframework.http.HttpStatus;

final class DriverException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final Map<String, Object> properties;

    DriverException(HttpStatus status, String code, String message) { this(status, code, message, Map.of()); }
    DriverException(HttpStatus status, String code, String message, Map<String, Object> properties) {
        super(message); this.status = status; this.code = code; this.properties = properties;
    }
    HttpStatus status() { return status; }
    String code() { return code; }
    Map<String, Object> properties() { return properties; }
}
