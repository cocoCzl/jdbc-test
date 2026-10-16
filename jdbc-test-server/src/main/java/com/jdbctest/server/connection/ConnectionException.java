package com.jdbctest.server.connection;

import java.util.Map;
import org.springframework.http.HttpStatus;

public final class ConnectionException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    ConnectionException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Map<String, Object> properties() {
        return Map.of();
    }
}
