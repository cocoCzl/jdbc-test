package com.jdbctest.extension;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class DriverManagerDataSourceTest {

    @Test
    void passesCredentialsAndVendorPropertiesToDriverManager() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                RecordingDriver.class.getName(),
                "jdbc:recording:test",
                "tester",
                "secret",
                Map.of("vendor.app.name", "jdbc-test")
        );

        try (Connection connection = dataSource.getConnection()) {
            assertNotNull(connection);
        }

        assertEquals("jdbc:recording:test", RecordingDriver.lastUrl);
        assertEquals("tester", RecordingDriver.lastProperties.getProperty("user"));
        assertEquals("secret", RecordingDriver.lastProperties.getProperty("password"));
        assertEquals("jdbc-test", RecordingDriver.lastProperties.getProperty("vendor.app.name"));
    }

    public static final class RecordingDriver implements Driver {
        static volatile String lastUrl;
        static volatile Properties lastProperties;

        static {
            try {
                DriverManager.registerDriver(new RecordingDriver());
            } catch (SQLException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        @Override
        public Connection connect(String url, Properties info) {
            if (!acceptsURL(url)) return null;
            lastUrl = url;
            lastProperties = new Properties();
            lastProperties.putAll(info);
            return (Connection) Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[] {Connection.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "close" -> null;
                        case "isClosed" -> false;
                        case "isWrapperFor" -> args[0] == Connection.class;
                        case "unwrap" -> args[0] == Connection.class ? proxy : null;
                        default -> defaultValue(method.getReturnType());
                    }
            );
        }

        private static Object defaultValue(Class<?> type) {
            if (!type.isPrimitive()) return null;
            if (type == boolean.class) return false;
            if (type == byte.class) return (byte) 0;
            if (type == short.class) return (short) 0;
            if (type == int.class) return 0;
            if (type == long.class) return 0L;
            if (type == float.class) return 0F;
            if (type == double.class) return 0D;
            if (type == char.class) return '\0';
            return null;
        }

        @Override public boolean acceptsURL(String url) { return url != null && url.startsWith("jdbc:recording:"); }
        @Override public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) { return new DriverPropertyInfo[0]; }
        @Override public int getMajorVersion() { return 1; }
        @Override public int getMinorVersion() { return 0; }
        @Override public boolean jdbcCompliant() { return false; }
        @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { throw new SQLFeatureNotSupportedException(); }
    }
}
