package com.jdbctest.datasource;

import static org.junit.jupiter.api.Assertions.*;

import com.jdbctest.extension.JdbcContracts;
import com.jdbctest.extension.JdbcTestExtension;
import com.jdbctest.extension.RequiresFeature;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(JdbcTestExtension.class)
@org.junit.jupiter.api.Tag("integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@RequiresFeature("data_source")
class DataSourceTest {

    private DataSource source;

    private DataSource getDataSource() throws Exception {
        if (source == null) {
            source = com.jdbctest.extension.VendorDataSources.dataSource(
                com.jdbctest.config.ConfigLoader.load().db);
        }
        return source;
    }

    @AfterEach
    void closeProvider() throws Exception {
        if (source instanceof AutoCloseable closeable) {
            closeable.close();
        }
    }

    @Test
    @Order(1)
    @DisplayName("DataSource 不应为 null")
    void testDataSourceNotNull() throws Exception {
        DataSource ds = getDataSource();
        assertNotNull(ds, "DataSource 不应为 null");
    }

    @Test
    @Order(2)
    @DisplayName("getConnection() 无参获取连接")
    void testGetConnection() throws Exception {
        DataSource ds = getDataSource();
        try (Connection conn = ds.getConnection()) {
            assertNotNull(conn, "getConnection() 应返回有效连接");
            assertFalse(conn.isClosed(), "新连接不应已关闭");
        }
    }

    @Test
    @Order(3)
    @DisplayName("getConnection(user, password) 带认证获取连接")
    void testGetConnectionWithAuth() throws Exception {
        DataSource ds = getDataSource();
        var config = com.jdbctest.config.ConfigLoader.load();
        try (Connection connection = ds.getConnection(config.db.username, config.db.password)) {
            assertFalse(connection.isClosed());
            assertTrue(connection.isValid(5));
        }
    }

    @Test
    @Order(4)
    @DisplayName("getLoginTimeout 和 setLoginTimeout")
    void testLoginTimeout() throws Exception {
        DataSource ds = getDataSource();
        int original = ds.getLoginTimeout();
        try {
            ds.setLoginTimeout(10);
            int timeout = ds.getLoginTimeout();
            assertEquals(10, timeout, "getLoginTimeout 应返回设置的超时");
        } finally {
            ds.setLoginTimeout(original);
        }
    }

    @Test
    @Order(5)
    @DisplayName("getLogWriter 和 setLogWriter")
    void testLogWriter() throws Exception {
        DataSource ds = getDataSource();
        try (PrintWriter pw = new PrintWriter(new StringWriter())) {
            PrintWriter original = ds.getLogWriter();
            try {
                ds.setLogWriter(pw);
                PrintWriter retrieved = ds.getLogWriter();
                assertSame(pw, retrieved);
            } finally {
                ds.setLogWriter(original);
            }
        }
    }

    @Test
    @Order(6)
    @DisplayName("getParentLogger 获取父 Logger")
    void testGetParentLogger(Connection connection) throws Exception {
        DataSource ds = getDataSource();
        Logger logger = com.jdbctest.extension.JdbcContracts.optional(
            "CommonDataSource.getParentLogger", ds::getParentLogger);
        assertParentLogger(logger, connection);
    }

    static void assertParentLogger(Logger logger, Connection connection) {
        try {
            assertNotNull(logger, "getParentLogger 应返回有效 Logger");
        } catch (AssertionError failure) {
            int major;
            int minor;
            try {
                DatabaseMetaData metadata = connection.getMetaData();
                major = metadata.getJDBCMajorVersion();
                minor = metadata.getJDBCMinorVersion();
            } catch (SQLException unavailable) {
                failure.addSuppressed(unavailable);
                throw new JdbcContracts.ContractApplicabilityAssertionError(
                    "CommonDataSource.getParentLogger requires JDBC 4.1; "
                        + "the driver's JDBC baseline could not be read", failure);
            }
            if (major < 4 || major == 4 && minor < 1) {
                throw new JdbcContracts.ContractApplicabilityAssertionError(
                    "CommonDataSource.getParentLogger requires JDBC 4.1; driver declares "
                        + major + "." + minor + "; a higher-version support commitment is unverified",
                    failure);
            }
            throw failure;
        }
    }

    @Test
    @Order(7)
    @DisplayName("多次获取连接不耗尽连接池")
    void testMultipleConnections() throws Exception {
        DataSource ds = getDataSource();
        for (int i = 0; i < 5; i++) {
            try (Connection conn = ds.getConnection()) {
                assertNotNull(conn);
                assertFalse(conn.isClosed());
            }
        }
    }

    @Test
    @DisplayName("关闭一个借用连接不影响另一个连接")
    void testIndependentConnectionLifetimes() throws Exception {
        try (Connection first = getDataSource().getConnection();
            Connection second = getDataSource().getConnection()) {
            first.close();
            assertTrue(first.isClosed());
            assertFalse(second.isClosed());
            assertTrue(second.isValid(5));
        }
    }
}
