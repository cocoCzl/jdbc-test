package com.jdbctest.server.connection;

import java.util.Map;
import org.springframework.http.HttpStatus;

public final class ConnectionException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Map<String, Object> properties;

    public ConnectionException(HttpStatus status, String code, String message) {
        this(status, code, message, Map.of());
    }

    public ConnectionException(HttpStatus status, String code, String message,
        Map<String, Object> properties) {
        super(message);
        this.properties = Map.copyOf(properties);
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
        return properties;
    }
}
