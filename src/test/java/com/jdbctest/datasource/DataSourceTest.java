package com.jdbctest.datasource;

import com.jdbctest.extension.JdbcContext;
import com.jdbctest.extension.JdbcTestExtension;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(JdbcTestExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DataSourceTest {

    private DataSource getDataSource() {
        return JdbcContext.getDataSource();
    }

    @Test
    @Order(1)
    @DisplayName("DataSource 不应为 null")
    void testDataSourceNotNull() {
        DataSource ds = getDataSource();
        assertNotNull(ds, "DataSource 不应为 null");
    }

    @Test
    @Order(2)
    @DisplayName("getConnection() 无参获取连接")
    void testGetConnection() throws SQLException {
        DataSource ds = getDataSource();
        try (Connection conn = ds.getConnection()) {
            assertNotNull(conn, "getConnection() 应返回有效连接");
            assertFalse(conn.isClosed(), "新连接不应已关闭");
        }
    }

    @Test
    @Order(3)
    @DisplayName("getConnection(user, password) 带认证获取连接")
    void testGetConnectionWithAuth() {
        DataSource ds = getDataSource();
        assertThrows(SQLException.class, () -> ds.getConnection("postgres", "postgres123"),
                "HikariCP 应拒绝带参数的 getConnection(user, password)");
    }

    @Test
    @Order(4)
    @DisplayName("getLoginTimeout 和 setLoginTimeout")
    void testLoginTimeout() throws SQLException {
        DataSource ds = getDataSource();
        ds.setLoginTimeout(10);
        int timeout = ds.getLoginTimeout();
        assertTrue(timeout >= 0, "getLoginTimeout 应返回非负值");
    }

    @Test
    @Order(5)
    @DisplayName("getLogWriter 和 setLogWriter")
    void testLogWriter() throws SQLException {
        DataSource ds = getDataSource();
        try (PrintWriter pw = new PrintWriter(System.out)) {
            ds.setLogWriter(pw);
            PrintWriter retrieved = ds.getLogWriter();
            assertNotNull(retrieved);
        } catch (SQLFeatureNotSupportedException e) {
            // HikariCP 不支持
        }
    }

    @Test
    @Order(6)
    @DisplayName("getParentLogger 获取父 Logger")
    void testGetParentLogger() {
        DataSource ds = getDataSource();
        try {
            Logger logger = ds.getParentLogger();
            assertNotNull(logger, "getParentLogger 应返回有效 Logger");
        } catch (SQLFeatureNotSupportedException ignore) {
            // HikariCP 不支持
        }
    }

    @Test
    @Order(7)
    @DisplayName("多次获取连接不耗尽连接池")
    void testMultipleConnections() throws SQLException {
        DataSource ds = getDataSource();
        for (int i = 0; i < 5; i++) {
            try (Connection conn = ds.getConnection()) {
                assertNotNull(conn);
                assertFalse(conn.isClosed());
            }
        }
    }
}
