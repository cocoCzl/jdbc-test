package com.jdbctest.server.run;

import org.springframework.http.HttpStatus;

public final class RunException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    RunException(HttpStatus status, String code, String message) { super(message); this.status = status; this.code = code; }
    public HttpStatus status() { return status; }
    public String code() { return code; }
}
