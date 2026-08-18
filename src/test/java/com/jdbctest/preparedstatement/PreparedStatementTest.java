package com.jdbctest.preparedstatement;

import com.jdbctest.config.Config;
import com.jdbctest.config.ConfigLoader;
import com.jdbctest.extension.JdbcTestExtension;
import com.jdbctest.extension.RequiresFeature;
import com.jdbctest.extension.UseSqlScripts;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;

import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(JdbcTestExtension.class)
@UseSqlScripts(
    ddl = {"preparedstatement_ddl.sql"},
    dml = {"preparedstatement_dml.sql"}
)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PreparedStatementTest {

    private static boolean isOracle() {
        return ConfigLoader.load().db.isDialect("oracle");
    }

    @Test
    @Order(1)
    @DisplayName("setString 绑定字符串参数")
    void testSetString(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM preparedstatement_test WHERE name = ?")) {
            ps.setString(1, "测试1");
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("测试1", rs.getString("name"));
                assertEquals(100, rs.getInt("value"));
            }
        }
    }

    @Test
    @Order(2)
    @DisplayName("setInt 绑定整数参数")
    void testSetInt(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM preparedstatement_test WHERE value = ?")) {
            ps.setInt(1, 100);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(100, rs.getInt("value"));
            }
        }
    }

    @Test
    @Order(3)
    @DisplayName("setLong 绑定长整型参数")
    void testSetLong(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM preparedstatement_test WHERE value > ?")) {
            ps.setLong(1, 150L);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertTrue(rs.getLong(1) > 0);
            }
        }
    }

    @Test
    @Order(4)
    @DisplayName("setDouble 绑定浮点数参数")
    void testSetDouble(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM preparedstatement_test WHERE amount > ?")) {
            ps.setDouble(1, 150.0);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertTrue(rs.getDouble("amount") > 150.0);
            }
        }
    }

    @Test
    @Order(5)
    @DisplayName("setBoolean 绑定布尔参数")
    void testSetBoolean(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM preparedstatement_test WHERE active = ?")) {
            ps.setBoolean(1, true);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertTrue(rs.getBoolean("active"));
            }
        }
    }

    @Test
    @Order(6)
    @DisplayName("setBigDecimal 绑定 BigDecimal 参数")
    void testSetBigDecimal(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM preparedstatement_test WHERE amount > ?")) {
            ps.setBigDecimal(1, new BigDecimal("100.00"));
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
            }
        }
    }

    @Test
    @Order(7)
    @DisplayName("setNull 绑定 NULL 参数")
    void testSetNull(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO preparedstatement_test (name, value, amount, active, description) VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, "NULL测试");
            ps.setNull(2, Types.INTEGER);
            ps.setNull(3, Types.DECIMAL);
            // Oracle doesn't support setNull with Types.BOOLEAN, use NUMERIC
            ps.setNull(4, isOracle() ? Types.NUMERIC : Types.BOOLEAN);
            ps.setNull(5, Types.VARCHAR);
            int count = ps.executeUpdate();
            assertEquals(1, count);
        }
    }

    @Test
    @Order(8)
    @DisplayName("setDate 绑定 Date 参数")
    void testSetDate(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM preparedstatement_test WHERE updated_at = ?")) {
            ps.setDate(1, Date.valueOf("2024-01-01"));
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(Date.valueOf("2024-01-01"), rs.getDate("updated_at"));
            }
        }
    }

    @Test
    @Order(9)
    @DisplayName("setTimestamp 绑定 Timestamp 参数")
    void testSetTimestamp(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM preparedstatement_test WHERE created_at > ?")) {
            ps.setTimestamp(1, Timestamp.valueOf("2024-01-15 00:00:00"));
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
            }
        }
    }

    @Test
    @Order(10)
    @DisplayName("setObject 绑定通用对象")
    void testSetObject(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM preparedstatement_test WHERE name = ?")) {
            ps.setObject(1, "测试1", Types.VARCHAR);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
            }
        }
    }

    @Test
    @Order(11)
    @DisplayName("executeQuery 预编译查询")
    void testExecuteQuery(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, name, value, amount, active FROM preparedstatement_test WHERE value < ? ORDER BY id")) {
            ps.setInt(1, 250);
            try (ResultSet rs = ps.executeQuery()) {
                assertNotNull(rs);
                int count = 0;
                while (rs.next()) {
                    count++;
                    assertNotNull(rs.getString("name"));
                }
                assertTrue(count >= 2, "应有至少 2 条记录");
            }
        }
    }

    @Test
    @Order(12)
    @DisplayName("executeUpdate 预编译更新")
    void testExecuteUpdate(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE preparedstatement_test SET value = ? WHERE name = ?")) {
            ps.setInt(1, 999);
            ps.setString(2, "测试1");
            int count = ps.executeUpdate();
            assertEquals(1, count);
        }
    }

    @Test
    @Order(13)
    @DisplayName("execute 预编译执行")
    void testExecute(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM preparedstatement_test WHERE id = ?")) {
            ps.setInt(1, 1);
            boolean hasResultSet = ps.execute();
            assertTrue(hasResultSet);
        }
    }

    @Test
    @Order(14)
    @DisplayName("批处理 addBatch / executeBatch")
    void testBatch(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO preparedstatement_test (name, value, amount, active) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, "批处理P1");
            ps.setInt(2, 111);
            ps.setBigDecimal(3, new BigDecimal("11.11"));
            ps.setBoolean(4, true);
            ps.addBatch();

            ps.setString(1, "批处理P2");
            ps.setInt(2, 222);
            ps.setBigDecimal(3, new BigDecimal("22.22"));
            ps.setBoolean(4, false);
            ps.addBatch();

            int[] results = ps.executeBatch();
            assertEquals(2, results.length);
            for (int r : results) {
                assertEquals(1, r);
            }
        }
    }

    @Test
    @Order(15)
    @DisplayName("getMetaData 获取结果集元数据")
    void testGetMetaData(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, name, value, amount, active, created_at FROM preparedstatement_test")) {
            ResultSetMetaData meta = ps.getMetaData();
            assertNotNull(meta);
            assertEquals(6, meta.getColumnCount(), "应有 6 列");
        }
    }

    @Test
    @Order(16)
    @RequiresFeature("parameter_metadata")
    @DisplayName("getParameterMetaData 获取参数元数据")
    void testGetParameterMetaData(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM preparedstatement_test WHERE name = ? AND value = ?")) {
            ParameterMetaData meta = ps.getParameterMetaData();
            assertNotNull(meta);
            assertTrue(meta.getParameterCount() >= 2, "应有至少 2 个参数");
        }
    }

    @Test
    @Order(17)
    @DisplayName("clearParameters 清除参数绑定")
    void testClearParameters(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM preparedstatement_test WHERE name = ?")) {
            ps.setString(1, "测试1");
            ps.clearParameters();
            // 清除后重新绑定再执行
            ps.setString(1, "测试2");
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("测试2", rs.getString("name"));
            }
        }
    }

    @Test
    @Order(18)
    @RequiresFeature("generated_keys")
    @DisplayName("getGeneratedKeys 获取自增主键")
    void testGetGeneratedKeys(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO preparedstatement_test (name, value) VALUES (?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, "主键测试PS");
            ps.setInt(2, 777);
            ps.executeUpdate();

            try (ResultSet keys = ps.getGeneratedKeys()) {
                assertTrue(keys.next());
                // Oracle returns ROWID string, not numeric
                if (isOracle()) {
                    assertNotNull(keys.getString(1));
                } else {
                    assertTrue(keys.getInt(1) > 0);
                }
            }
        }
    }

    @Test
    @Order(19)
    @DisplayName("二进制参数 setBytes/getBytes 往返")
    void testBinaryParameterRoundTrip(Connection conn) throws SQLException {
        byte[] expected = {0, 1, 2, 127, -1};
        try (PreparedStatement update = conn.prepareStatement(
                "UPDATE preparedstatement_test SET data = ? WHERE id = 1")) {
            update.setBytes(1, expected);
            assertEquals(1, update.executeUpdate());
        }
        try (PreparedStatement query = conn.prepareStatement(
                "SELECT data FROM preparedstatement_test WHERE id = 1");
             ResultSet rs = query.executeQuery()) {
            assertTrue(rs.next());
            assertArrayEquals(expected, rs.getBytes(1));
        }
    }

    @Test
    @Order(20)
    @RequiresFeature("java_time")
    @DisplayName("setObject/getObject(Class) Java 时间类型")
    void testJavaTimeObjectMapping(Connection conn) throws SQLException {
        LocalDate expected = LocalDate.of(2024, 6, 15);
        try (PreparedStatement update = conn.prepareStatement(
                "UPDATE preparedstatement_test SET updated_at = ? WHERE id = 1")) {
            update.setObject(1, expected);
            assertEquals(1, update.executeUpdate());
        }
        try (PreparedStatement query = conn.prepareStatement(
                "SELECT updated_at FROM preparedstatement_test WHERE id = 1");
             ResultSet rs = query.executeQuery()) {
            assertTrue(rs.next());
            assertEquals(expected, rs.getObject(1, LocalDate.class));
        }
    }

    @Test
    @Order(21)
    @DisplayName("批处理失败返回 BatchUpdateException")
    void testBatchFailureReportsUpdateCounts(Connection conn) throws SQLException {
        boolean autoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO preparedstatement_test (id, name, value) VALUES (?, ?, ?)")) {
            ps.setInt(1, 1); // ID 1 is seeded by every adapter's SQL asset.
            ps.setString(2, "duplicate-key");
            ps.setInt(3, 1);
            ps.addBatch();
            BatchUpdateException error = assertThrows(BatchUpdateException.class, ps::executeBatch);
            assertNotNull(error.getUpdateCounts(), "BatchUpdateException 必须提供更新计数");
        } finally {
            conn.rollback();
            conn.setAutoCommit(autoCommit);
        }
    }

    @Test
    @Order(22)
    @DisplayName("isClosed 和 close 状态")
    void testIsClosed(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT 1")) {
            assertFalse(ps.isClosed());
            ps.close();
            assertTrue(ps.isClosed());
        }
    }
}
