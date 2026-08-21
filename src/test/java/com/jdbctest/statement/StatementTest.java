package com.jdbctest.statement;

import com.jdbctest.config.ConfigLoader;
import com.jdbctest.extension.JdbcTestExtension;
import com.jdbctest.extension.UseSqlScripts;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;

import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(JdbcTestExtension.class)
@UseSqlScripts(
    ddl = {"statement_ddl.sql"},
    dml = {"statement_dml.sql"}
)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StatementTest {

    private static boolean isOracle() {
        return ConfigLoader.load().db.isDialect("oracle");
    }

    @Test
    @Order(1)
    @DisplayName("execute 执行 SELECT 查询")
    void testExecuteSelect(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            boolean hasResultSet = stmt.execute("SELECT * FROM statement_test WHERE value > 15");
            assertTrue(hasResultSet, "SELECT 应返回 ResultSet");
        }
    }

    @Test
    @Order(2)
    @DisplayName("execute 执行 INSERT 语句")
    void testExecuteInsert(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            String activeVal = isOracle() ? "1" : "true";
            boolean hasResultSet = stmt.execute("INSERT INTO statement_test (name, value, active) VALUES ('execute测试', 99, " + activeVal + ")");
            assertFalse(hasResultSet, "INSERT 不应返回 ResultSet");
            assertEquals(1, stmt.getUpdateCount(), "INSERT 应影响 1 行");
        }
    }

    @Test
    @Order(3)
    @DisplayName("execute 执行 UPDATE 语句")
    void testExecuteUpdate(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            boolean hasResultSet = stmt.execute("UPDATE statement_test SET value = 999 WHERE name = 'execute测试'");
            assertFalse(hasResultSet);
            assertEquals(1, stmt.getUpdateCount(), "UPDATE 应影响 1 行");
        }
    }

    @Test
    @Order(4)
    @DisplayName("execute 执行 DELETE 语句")
    void testExecuteDelete(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            boolean hasResultSet = stmt.execute("DELETE FROM statement_test WHERE name = 'execute测试'");
            assertFalse(hasResultSet);
            assertEquals(1, stmt.getUpdateCount(), "DELETE 应影响 1 行");
        }
    }

    @Test
    @Order(5)
    @DisplayName("executeQuery 执行查询")
    void testExecuteQuery(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id, name, value, active FROM statement_test ORDER BY id")) {
            assertNotNull(rs, "executeQuery 应返回 ResultSet");
            assertTrue(rs.next(), "应有数据行");
            assertEquals("批量1", rs.getString("name"));
        }
    }

    @Test
    @Order(6)
    @DisplayName("executeUpdate 返回影响行数")
    void testExecuteUpdateRowCount(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            int count = stmt.executeUpdate("UPDATE statement_test SET value = 55 WHERE name = '批量1'");
            assertEquals(1, count, "executeUpdate 应返回影响行数");
        }
    }

    @Test
    @Order(7)
    @DisplayName("批处理 executeBatch")
    void testExecuteBatch(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            String t = isOracle() ? "1" : "true";
            String f = isOracle() ? "0" : "false";
            stmt.addBatch("INSERT INTO statement_test (name, value, active) VALUES ('批处理A', 1, " + t + ")");
            stmt.addBatch("INSERT INTO statement_test (name, value, active) VALUES ('批处理B', 2, " + f + ")");
            stmt.addBatch("INSERT INTO statement_test (name, value, active) VALUES ('批处理C', 3, " + t + ")");

            int[] results = stmt.executeBatch();
            assertEquals(3, results.length, "应执行 3 条批处理语句");
            for (int r : results) {
                assertEquals(1, r, "每条 INSERT 应影响 1 行");
            }
        }
    }

    @Test
    @Order(8)
    @DisplayName("clearBatch 清空批处理")
    void testClearBatch(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.addBatch("INSERT INTO statement_test (name, value) VALUES ('清空测试', 50)");
            stmt.clearBatch();
            int[] results = stmt.executeBatch();
            assertEquals(0, results.length, "clearBatch 后 executeBatch 应无语句执行");
        }
    }

    @Test
    @Order(9)
    @DisplayName("getResultSet 获取执行结果")
    void testGetResultSet(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("SELECT COUNT(*) FROM statement_test");
            try (ResultSet rs = stmt.getResultSet()) {
                assertNotNull(rs, "getResultSet 应返回非空");
                rs.next();
                assertTrue(rs.getInt(1) > 0, "计数应大于 0");
            }
        }
    }

    @Test
    @Order(10)
    @DisplayName("getUpdateCount 获取更新计数")
    void testGetUpdateCount(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO statement_test (name, value) VALUES ('updateCount测试', 100)");
            assertEquals(1, stmt.getUpdateCount());
        }
    }

    @Test
    @Order(11)
    @DisplayName("getMoreResults 多个结果集")
    void testGetMoreResults(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            String sql = isOracle() ? "SELECT 1 AS n FROM DUAL" : "SELECT 1 AS n";
            stmt.execute(sql);
            assertNotNull(stmt.getResultSet(), "execute SELECT 后 getResultSet 不应为 null");

            boolean hasMore = stmt.getMoreResults();
            assertFalse(hasMore, "单 SELECT 语句 getMoreResults 应返回 false");
        }
    }

    @Test
    @Order(12)
    @DisplayName("getMaxRows 和 setMaxRows")
    void testMaxRows(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.setMaxRows(2);
            assertEquals(2, stmt.getMaxRows(), "getMaxRows 应返回 2");

            try (ResultSet rs = stmt.executeQuery("SELECT * FROM statement_test")) {
                int count = 0;
                while (rs.next()) count++;
                assertTrue(count <= 2, "行数不应超过 MaxRows");
            }

            stmt.setMaxRows(0);
            assertEquals(0, stmt.getMaxRows(), "setMaxRows(0) 表示无限制");
        }
    }

    @Test
    @Order(13)
    @DisplayName("getQueryTimeout 和 setQueryTimeout")
    void testQueryTimeout(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.setQueryTimeout(10);
            assertEquals(10, stmt.getQueryTimeout(), "getQueryTimeout 应返回 10");

            stmt.setQueryTimeout(0);
            assertEquals(0, stmt.getQueryTimeout(), "setQueryTimeout(0) 表示无限制");
        }
    }

    @Test
    @Order(14)
    @DisplayName("getGeneratedKeys 获取自增主键")
    void testGetGeneratedKeys(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("INSERT INTO statement_test (name, value) VALUES ('主键测试', 88)",
                    Statement.RETURN_GENERATED_KEYS);

            try (ResultSet keys = stmt.getGeneratedKeys()) {
                assertTrue(keys.next(), "应有生成的主键");
                // Oracle returns ROWID as string, others return numeric
                if (isOracle()) {
                    assertNotNull(keys.getString(1), "主键不应为 null");
                } else {
                    assertTrue(keys.getInt(1) > 0, "主键应大于 0");
                }
            }
        }
    }

    @Test
    @Order(15)
    @DisplayName("getConnection 获取关联的连接")
    void testGetConnection(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            Connection stmtConn = stmt.getConnection();
            assertNotNull(stmtConn, "getConnection 不应返回 null");
            assertSame(conn, stmtConn, "getConnection 应返回同一连接");
        }
    }

    @Test
    @Order(16)
    @DisplayName("isClosed 和 close 状态检查")
    void testIsClosed(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            assertFalse(stmt.isClosed(), "新 Statement 不应关闭");
            stmt.close();
            assertTrue(stmt.isClosed(), "关闭后 isClosed 应返回 true");
        }
    }

    @Test
    @Order(17)
    @DisplayName("getWarnings 获取警告信息")
    void testGetWarnings(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.clearWarnings();
            SQLWarning warning = stmt.getWarnings();
            assertNull(warning, "clearWarnings 后 getWarnings 应返回 null");
        }
    }

    @Test
    @Order(18)
    @DisplayName("getFetchSize 和 setFetchSize")
    void testFetchSize(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.setFetchSize(50);
            assertEquals(50, stmt.getFetchSize(), "getFetchSize 应返回 50");
        }
    }

    @Test
    @Order(19)
    @DisplayName("executeUpdate 执行 DDL（返回0行）")
    void testExecuteUpdateDDL(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            // cleanup: DROP with Oracle-compatible syntax
            try {
                if (isOracle()) {
                    stmt.execute("DROP TABLE temp_test_ddl CASCADE CONSTRAINTS PURGE");
                } else {
                    stmt.execute("DROP TABLE IF EXISTS temp_test_ddl");
                }
            } catch (SQLException ignored) {
                // table may not exist
            }
            int count = stmt.executeUpdate("CREATE TABLE temp_test_ddl (id INT)");
            assertEquals(0, count, "DDL 语句应返回 0");
            if (isOracle()) {
                stmt.execute("DROP TABLE temp_test_ddl CASCADE CONSTRAINTS PURGE");
            } else {
                stmt.execute("DROP TABLE IF EXISTS temp_test_ddl");
            }
        }
    }

    @Test
    @Order(20)
    @DisplayName("Statement poolable hint")
    void testPoolable(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.setPoolable(true);
            assertDoesNotThrow(stmt::isPoolable, "isPoolable 不应抛异常");

            stmt.setPoolable(false);
            assertDoesNotThrow(stmt::isPoolable, "关闭 poolable hint 后 isPoolable 不应抛异常");
        }
    }

    @Test
    @Order(21)
    @DisplayName("closeOnCompletion 自动关闭 Statement")
    void testCloseOnCompletion(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.closeOnCompletion();
            assertTrue(stmt.isCloseOnCompletion(), "closeOnCompletion 后状态应为 true");

            ResultSet rs = stmt.executeQuery(isOracle() ? "SELECT 1 FROM DUAL" : "SELECT 1");
            assertFalse(stmt.isClosed(), "ResultSet 关闭前 Statement 不应关闭");
            rs.close();
            assertTrue(stmt.isClosed(), "ResultSet 关闭后 Statement 应自动关闭");
        }
    }

    @Test
    @Order(22)
    @DisplayName("executeLargeUpdate 返回长整型影响行数")
    void testExecuteLargeUpdate(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            long count = stmt.executeLargeUpdate(
                    "UPDATE statement_test SET value = value WHERE name = '批量1'");
            assertEquals(1L, count, "executeLargeUpdate 应返回影响行数");
            assertEquals(1L, stmt.getLargeUpdateCount(), "getLargeUpdateCount 应返回最近影响行数");
        }
    }

    @Test
    @Order(23)
    @DisplayName("executeLargeBatch 批处理")
    void testExecuteLargeBatch(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            String t = isOracle() ? "1" : "true";
            stmt.addBatch("INSERT INTO statement_test (name, value, active) VALUES ('大批处理A', 11, " + t + ")");
            stmt.addBatch("INSERT INTO statement_test (name, value, active) VALUES ('大批处理B', 12, " + t + ")");

            long[] results = stmt.executeLargeBatch();
            assertEquals(2, results.length, "应执行 2 条批处理语句");
            for (long result : results) {
                assertEquals(1L, result, "每条 INSERT 应影响 1 行");
            }
        }
    }
}
