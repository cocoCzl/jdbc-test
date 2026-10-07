package com.jdbctest.extension;

import com.jdbctest.config.Config;
import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.*;
import java.util.logging.Logger;

/** Initializes every physical/pooled checkout; JDBC Connection methods remain the actual driver's methods. */
final class ScopedDataSource implements DataSource, AutoCloseable {
    private final DataSource delegate;
    private final Config config;
    ScopedDataSource(DataSource delegate, Config config) { this.delegate = delegate; this.config = config; }
    private Connection scope(Connection connection) throws SQLException {
        try {
            if ("catalog".equals(config.namespace.selection)) {
                connection.setCatalog(config.namespace.name);
                if (!config.namespace.name.equals(connection.getCatalog())) throw new SQLException("EXECUTION_SCOPE_MISMATCH: catalog");
            } else {
                if (config.namespace.database != null && !config.namespace.database.isBlank()
                        && !config.namespace.database.equals(connection.getCatalog())) throw new SQLException("EXECUTION_SCOPE_MISMATCH: database");
                connection.setSchema(config.namespace.name);
                if (!config.namespace.name.equals(connection.getSchema())) throw new SQLException("EXECUTION_SCOPE_MISMATCH: schema");
            }
            return connection;
        } catch (SQLException | RuntimeException exception) { connection.close(); throw exception; }
    }
    @Override public Connection getConnection() throws SQLException { return scope(delegate.getConnection()); }
    @Override public Connection getConnection(String user, String password) throws SQLException { return scope(java.util.Objects.equals(user, config.db.username)
            && java.util.Objects.equals(password, config.db.password) ? delegate.getConnection() : delegate.getConnection(user, password)); }
    @Override public PrintWriter getLogWriter() throws SQLException { return delegate.getLogWriter(); }
    @Override public void setLogWriter(PrintWriter value) throws SQLException { delegate.setLogWriter(value); }
    @Override public int getLoginTimeout() throws SQLException { return delegate.getLoginTimeout(); }
    @Override public void setLoginTimeout(int value) throws SQLException { delegate.setLoginTimeout(value); }
    @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { return delegate.getParentLogger(); }
    @Override public boolean isWrapperFor(Class<?> type) throws SQLException { return type.isInstance(this) || delegate.isWrapperFor(type); }
    @Override public <T> T unwrap(Class<T> type) throws SQLException { return type.isInstance(this) ? type.cast(this) : delegate.unwrap(type); }
    @Override public void close() throws Exception { if (delegate instanceof AutoCloseable closeable) closeable.close(); }
}
