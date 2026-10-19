package com.jdbctest.extension;

import java.sql.SQLException;

/** An observed target connection failure, distinct from missing platform configuration. */
public final class TargetConnectionException extends SQLException {

    public TargetConnectionException(SQLException cause) {
        super("Target connection failed", cause.getSQLState(), cause.getErrorCode(), cause);
    }
}
