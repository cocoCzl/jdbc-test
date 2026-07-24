package com.jdbctest.extension;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Logger;

/** A small DataSource facade that preserves DriverManager connection semantics. */
final class DriverManagerDataSource implements DataSource {

    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final Properties baseProperties;

    DriverManagerDataSource(
            String driverClass,
            String jdbcUrl,
            String username,
            String password,
            Map<String, String> properties
    ) {
        if (driverClass == null || driverClass.isBlank()) {
            throw new IllegalArgumentException("DriverManager 模式必须配置 db.driver_class");
        }
        try {
            Class.forName(driverClass);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("无法加载 JDBC 驱动类: " + driverClass, e);
        }
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
        this.baseProperties = new Properties();
        if (properties != null) this.baseProperties.putAll(properties);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return connect(username, password);
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return connect(username, password);
    }

    private Connection connect(String connectionUsername, String connectionPassword) throws SQLException {
        Properties properties = new Properties();
        properties.putAll(baseProperties);
        if (connectionUsername != null) properties.setProperty("user", connectionUsername);
        if (connectionPassword != null) properties.setProperty("password", connectionPassword);
        return DriverManager.getConnection(jdbcUrl, properties);
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return DriverManager.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        DriverManager.setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        DriverManager.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return DriverManager.getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return Logger.getLogger("java.sql.DriverManager");
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface != null && iface.isInstance(this)) return iface.cast(this);
        throw new SQLException("Not a wrapper for " + (iface == null ? "null" : iface.getName()));
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface != null && iface.isInstance(this);
    }
}
