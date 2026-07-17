package com.jdbctest.connection;

import com.jdbctest.config.Config;
import com.jdbctest.config.ConfigLoader;
import com.jdbctest.extension.JdbcTestExtension;
import com.jdbctest.extension.UseSqlScripts;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;

import java.sql.*;
import java.util.Properties;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(JdbcTestExtension.class)
@UseSqlScripts(
    ddl = {"connection_ddl.sql"},
    dml = {"connection_dml.sql"}
)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ConnectionTest {

    private static boolean isOracle() {
        return ConfigLoader.load().db.isDialect("oracle");
    }

    @Test
    @Order(1)
    @DisplayName("获取自动提交模式")
    void testGetAutoCommit(Connection conn) throws SQLException {
        assertDoesNotThrow(conn::getAutoCommit, "getAutoCommit 不应抛异常");
        boolean autoCommit = conn.getAutoCommit();
        assertFalse(conn.isClosed(), "未关闭的连接 getAutoCommit 不应失败");
    }

    @Test
    @Order(2)
    @DisplayName("设置自动提交模式")
    void testSetAutoCommit(Connection conn) throws SQLException {
        conn.setAutoCommit(false);
        assertFalse(conn.getAutoCommit(), "关闭自动提交后 getAutoCommit 应返回 false");

        conn.setAutoCommit(true);
        assertTrue(conn.getAutoCommit(), "恢复自动提交后 getAutoCommit 应返回 true");
    }

    @Test
    @Order(3)
    @DisplayName("手动提交事务")
    void testCommit(Connection conn) throws SQLException {
        conn.setAutoCommit(false);
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM connection_test WHERE name = 'commit_test'");
        }
        conn.commit();

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM connection_test WHERE name = 'commit_test'")) {
            rs.next();
            assertEquals(0, rs.getInt(1), "提交后删除操作应生效");
        } finally {
            conn.setAutoCommit(true);
        }
    }

    @Test
    @Order(4)
    @DisplayName("回滚事务")
    void testRollback(Connection conn) throws SQLException {
        conn.setAutoCommit(false);
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM connection_test WHERE id > 0");
        }
        conn.rollback();

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM connection_test")) {
            rs.next();
            assertTrue(rs.getInt(1) > 0, "回滚后数据应恢复");
        } finally {
            conn.setAutoCommit(true);
        }
    }

    @Test
    @Order(5)
    @DisplayName("获取和设置事务隔离级别")
    void testTransactionIsolation(Connection conn) throws SQLException {
        int original = conn.getTransactionIsolation();

        conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        assertEquals(Connection.TRANSACTION_READ_COMMITTED, conn.getTransactionIsolation(),
                "设置为 READ_COMMITTED 后应返回相同值");

        // Oracle only supports READ_COMMITTED and SERIALIZABLE
        if (!isOracle()) {
            conn.setTransactionIsolation(Connection.TRANSACTION_READ_UNCOMMITTED);
            assertEquals(Connection.TRANSACTION_READ_UNCOMMITTED, conn.getTransactionIsolation());
        }

        // 恢复原始级别
        conn.setTransactionIsolation(original);
    }

    @Test
    @Order(6)
    @DisplayName("获取和设置只读模式")
    void testReadOnly(Connection conn) throws SQLException {
        conn.setReadOnly(true);
        assertTrue(conn.isReadOnly(), "设置只读后 isReadOnly 应返回 true");

        conn.setReadOnly(false);
        assertFalse(conn.isReadOnly(), "取消只读后 isReadOnly 应返回 false");
    }

    @Test
    @Order(7)
    @DisplayName("创建 Statement")
    void testCreateStatement(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            assertNotNull(stmt, "createStatement 不应返回 null");
            assertFalse(stmt.isClosed(), "新创建的 Statement 不应关闭");
        }
    }

    @Test
    @Order(8)
    @DisplayName("创建 PreparedStatement")
    void testPrepareStatement(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM connection_test WHERE id = ?")) {
            assertNotNull(ps, "prepareStatement 不应返回 null");
            ps.setInt(1, 1);
            try (ResultSet rs = ps.executeQuery()) {
                assertNotNull(rs);
            }
        }
    }

    @Test
    @Order(9)
    @DisplayName("连接有效性检查")
    void testIsValid(Connection conn) throws SQLException {
        assertTrue(conn.isValid(5), "有效连接 isValid 应返回 true");
    }

    @Test
    @Order(10)
    @DisplayName("获取数据库元数据")
    void testGetMetaData(Connection conn) throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();
        assertNotNull(meta, "getMetaData 不应返回 null");
        assertNotNull(meta.getDatabaseProductName(), "数据库产品名不为空");
        assertNotNull(meta.getDriverName(), "驱动名不为空");
        assertFalse(meta.getDriverVersion().isEmpty(), "驱动版本不为空");
    }

    @Test
    @Order(11)
    @DisplayName("获取和清空警告")
    void testWarnings(Connection conn) throws SQLException {
        conn.clearWarnings();
        SQLWarning warning = conn.getWarnings();
        assertNull(warning, "clearWarnings 后 getWarnings 应返回 null");
    }

    @Test
    @Order(12)
    @DisplayName("关闭连接")
    void testClose(Connection conn) throws SQLException {
        assertFalse(conn.isClosed(), "活跃连接 isClosed 应返回 false");
        conn.close();
        assertTrue(conn.isClosed(), "关闭后 isClosed 应返回 true");
    }

    @Test
    @Order(13)
    @DisplayName("nativeSQL 转换 SQL")
    void testNativeSql(Connection conn) throws SQLException {
        String sql = isOracle() ? "SELECT 1 FROM DUAL" : "SELECT 1";
        String nativeSql = conn.nativeSQL(sql);
        assertNotNull(nativeSql, "nativeSQL 不应返回 null");
        assertFalse(nativeSql.isBlank(), "nativeSQL 不应返回空字符串");
    }

    @Test
    @Order(14)
    @DisplayName("获取 catalog 和 schema")
    void testCatalogAndSchema(Connection conn) throws SQLException {
        assertDoesNotThrow(conn::getCatalog, "getCatalog 不应抛异常");
        assertDoesNotThrow(conn::getSchema, "getSchema 不应抛异常");

        String catalog = conn.getCatalog();
        if (catalog != null && !catalog.isBlank()) {
            assertDoesNotThrow(() -> conn.setCatalog(catalog), "setCatalog 为当前 catalog 不应失败");
        }

        String schema = conn.getSchema();
        if (schema != null && !schema.isBlank()) {
            assertDoesNotThrow(() -> conn.setSchema(schema), "setSchema 为当前 schema 不应失败");
        }
    }

    @Test
    @Order(15)
    @DisplayName("获取和设置 ResultSet holdability")
    void testHoldability(Connection conn) throws SQLException {
        int holdability = conn.getHoldability();
        assertTrue(holdability == ResultSet.HOLD_CURSORS_OVER_COMMIT
                        || holdability == ResultSet.CLOSE_CURSORS_AT_COMMIT,
                "getHoldability 应返回 JDBC 定义值");

        DatabaseMetaData meta = conn.getMetaData();
        if (meta.supportsResultSetHoldability(holdability)) {
            conn.setHoldability(holdability);
            assertEquals(holdability, conn.getHoldability(), "设置当前 holdability 后应保持一致");
        }
    }

    @Test
    @Order(16)
    @DisplayName("ClientInfo 读取")
    void testClientInfo(Connection conn) throws SQLException {
        assertDoesNotThrow(() -> conn.getClientInfo(), "getClientInfo 不应抛异常");
        Properties clientInfo = conn.getClientInfo();
        assertNotNull(clientInfo, "getClientInfo 不应返回 null");
    }

    @Test
    @Order(17)
    @DisplayName("NetworkTimeout 获取和设置")
    void testNetworkTimeout(Connection conn) throws SQLException {
        int original = conn.getNetworkTimeout();
        Executor directExecutor = Runnable::run;

        conn.setNetworkTimeout(directExecutor, original);
        assertEquals(original, conn.getNetworkTimeout(), "设置为当前 network timeout 后应保持一致");
    }
}
