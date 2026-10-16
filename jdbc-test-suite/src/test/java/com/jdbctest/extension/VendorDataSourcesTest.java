package com.jdbctest.extension;

import static org.junit.jupiter.api.Assertions.*;

import com.jdbctest.catalog.DataSourceProviders;
import com.jdbctest.config.Config;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Map;
import java.util.logging.Logger;
import javax.sql.ConnectionPoolDataSource;
import javax.sql.DataSource;
import javax.sql.PooledConnection;
import javax.sql.XAConnection;
import javax.sql.XADataSource;
import org.junit.jupiter.api.Test;

class VendorDataSourcesTest {

    private Config.DbConfig config() {
        Config.DbConfig config = new Config.DbConfig();
        config.url = "jdbc:fixture:test";
        config.username = "tester";
        config.password = "fixture-${LITERAL}-$\\password";
        var provider = new DataSourceProviders.Provider(Fixture.class.getName(), "jdbcUrl",
            "username", "secret");
        config.dataSources = new DataSourceProviders(provider, provider, provider);
        config.properties = Map.of("port", "1234", "enabled", "true");
        return config;
    }

    @Test
    void loadsArbitraryClassesForAllThreeStandardInterfacesWithoutWrapping() throws Exception {
        Config.DbConfig config = config();
        for (Object source : new Object[] {VendorDataSources.dataSource(config),
            VendorDataSources.pooled(config), VendorDataSources.xa(config)}) {
            Fixture fixture = assertInstanceOf(Fixture.class, source);
            assertEquals(config.url, fixture.url);
            assertEquals(config.username, fixture.user);
            assertEquals(config.password, fixture.password);
            assertEquals(1234, fixture.port);
            assertTrue(fixture.enabled);
        }
    }

    @Test
    void rejectsMissingProviderWrongInterfaceAndCredentialOverrides() {
        Config.DbConfig config = config();
        config.dataSources = DataSourceProviders.empty();
        assertThrows(IllegalStateException.class, () -> VendorDataSources.dataSource(config));
        var wrong = new DataSourceProviders.Provider(String.class.getName(), null, null, null);
        config.dataSources = new DataSourceProviders(wrong, null, null);
        assertThrows(IllegalStateException.class, () -> VendorDataSources.dataSource(config));
        Config.DbConfig collision = config();
        collision.properties = Map.of("secret", "override");
        assertThrows(IllegalStateException.class, () -> VendorDataSources.dataSource(collision));
    }

    @Test
    void rejectsUnknownPropertiesAndDoesNotExposeCredentialsFromSetterErrors() {
        Config.DbConfig config = config();
        config.properties = Map.of("unknown", "value");
        assertThrows(IllegalStateException.class, () -> VendorDataSources.dataSource(config));
        config.properties = Map.of();
        config.password = "reject";
        Exception error = assertThrows(IllegalStateException.class,
            () -> VendorDataSources.dataSource(config));
        assertFalse(error.toString().contains("reject"));
        assertNull(error.getCause());
    }

    public static class Fixture implements DataSource, ConnectionPoolDataSource, XADataSource {

        String url;
        String user;
        String password;
        int port;
        boolean enabled;

        public void setJdbcUrl(String value) {
            url = value;
        }

        public void setUsername(String value) {
            user = value;
        }

        public void setSecret(String value) {
            if ("reject".equals(value)) {
                throw new IllegalArgumentException("secret=" + value);
            }
            password = value;
        }

        public void setPort(int value) {
            port = value;
        }

        public void setEnabled(boolean value) {
            enabled = value;
        }

        public Connection getConnection() throws SQLException {
            throw new SQLFeatureNotSupportedException();
        }

        public Connection getConnection(String u, String p) throws SQLException {
            return getConnection();
        }

        public PooledConnection getPooledConnection() throws SQLException {
            throw new SQLFeatureNotSupportedException();
        }

        public PooledConnection getPooledConnection(String u, String p) throws SQLException {
            return getPooledConnection();
        }

        public XAConnection getXAConnection() throws SQLException {
            throw new SQLFeatureNotSupportedException();
        }

        public XAConnection getXAConnection(String u, String p) throws SQLException {
            return getXAConnection();
        }

        public PrintWriter getLogWriter() {
            return null;
        }

        public void setLogWriter(PrintWriter value) {
        }

        public int getLoginTimeout() {
            return 0;
        }

        public void setLoginTimeout(int value) {
        }

        public Logger getParentLogger() {
            return Logger.getGlobal();
        }

        public boolean isWrapperFor(Class<?> type) {
            return type.isInstance(this);
        }

        public <T> T unwrap(Class<T> type) {
            return type.cast(this);
        }
    }
}
