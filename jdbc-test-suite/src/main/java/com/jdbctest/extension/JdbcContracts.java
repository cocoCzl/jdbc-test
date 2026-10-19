package com.jdbctest.extension;

import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;

/** Optional outcomes must be scoped to the particular JDBC API call that permits them. */
public final class JdbcContracts {

    private JdbcContracts() {
    }

    @FunctionalInterface
    public interface SqlCall<T> {
        T call() throws SQLException;
    }

    public static <T> T optional(String api, SqlCall<T> call) throws SQLException {
        if (!java.util.Set.of("Connection.createBlob", "Connection.createClob",
            "Connection.createSQLXML", "CommonDataSource.getParentLogger").contains(api)) {
            throw new IllegalArgumentException("No reviewed optional API contract: " + api);
        }
        try {
            return call.call();
        } catch (SQLFeatureNotSupportedException error) {
            throw new UnsupportedAllowedException(api, error);
        }
    }

    public static <T> T required(String api, SqlCall<T> call) throws SQLException {
        if (!"Connection.getAutoCommit".equals(api)) {
            throw new IllegalArgumentException("No reviewed required API contract: " + api);
        }
        try {
            return call.call();
        } catch (SQLFeatureNotSupportedException error) {
            throw new UnsupportedRequiredException(api, error);
        }
    }

    public static final class UnsupportedRequiredException extends SQLException {

        private UnsupportedRequiredException(String api, SQLFeatureNotSupportedException cause) {
            super("JDBC requires API: " + api, cause.getSQLState(), cause.getErrorCode(), cause);
        }
    }

    public static final class UnsupportedAllowedException extends org.opentest4j.TestAbortedException {

        private UnsupportedAllowedException(String api, SQLFeatureNotSupportedException cause) {
            super("JDBC permits unsupported API: " + api, cause);
        }
    }
}
