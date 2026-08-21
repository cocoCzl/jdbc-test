package com.jdbctest.resultset;

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
    ddl = {"resultset_ddl.sql"},
    dml = {"resultset_dml.sql"}
)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ResultSetTest {

    private static boolean isOracle() {
        return ConfigLoader.load().db.isDialect("oracle");
    }

    @Test
    @Order(1)
    @DisplayName("next 正向遍历")
    void testNext(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM resultset_test ORDER BY id")) {
            int count = 0;
            while (rs.next()) {
                count++;
            }
            assertEquals(5, count, "应有 5 行数据");
        }
    }

    @Test
    @Order(2)
    @DisplayName("previous 反向移动")
    void testPrevious(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement(
                ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY);
             ResultSet rs = stmt.executeQuery("SELECT * FROM resultset_test ORDER BY id")) {
            rs.last(); // 移动到第 5 行
            int count = 0;
            while (rs.previous()) {
                count++;
            }
            assertTrue(count >= 4, "应从最后一行的前一行开始向前遍历至少 4 行");
        }
    }

    @Test
    @Order(3)
    @DisplayName("first 移动到第一行")
    void testFirst(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement(
                ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY);
             ResultSet rs = stmt.executeQuery("SELECT * FROM resultset_test ORDER BY id")) {
            assertTrue(rs.first(), "first() 应成功");
            assertEquals(1, rs.getInt("id"));
        }
    }

    @Test
    @Order(4)
    @DisplayName("last 移动到最后一行")
    void testLast(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement(
                ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY);
             ResultSet rs = stmt.executeQuery("SELECT * FROM resultset_test ORDER BY id")) {
            assertTrue(rs.last(), "last() 应成功");
            assertEquals(5, rs.getInt("id"));
        }
    }

    @Test
    @Order(5)
    @DisplayName("absolute 绝对定位")
    void testAbsolute(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement(
                ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY);
             ResultSet rs = stmt.executeQuery("SELECT * FROM resultset_test ORDER BY id")) {
            assertTrue(rs.absolute(3), "absolute(3) 应成功");
            assertEquals(3, rs.getInt("id"));
            assertTrue(rs.absolute(1), "absolute(1) 应跳到第一行");
            assertFalse(rs.absolute(0), "absolute(0) 应在 beforeFirst");
            assertTrue(rs.isBeforeFirst(), "absolute(0) 应在 beforeFirst");
            assertFalse(rs.absolute(999), "absolute(999) 超出行数应返回 false");
            assertTrue(rs.isAfterLast(), "超出后应在 afterLast");
        }
    }

    @Test
    @Order(6)
    @DisplayName("relative 相对移动")
    void testRelative(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement(
                ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY);
             ResultSet rs = stmt.executeQuery("SELECT * FROM resultset_test ORDER BY id")) {
            rs.absolute(2);
            assertTrue(rs.relative(2), "relative(2) 向前移动 2 行应成功");
            assertEquals(4, rs.getInt("id"));
            assertTrue(rs.relative(-1), "relative(-1) 向后移动应成功");
            assertEquals(3, rs.getInt("id"));
        }
    }

    @Test
    @Order(7)
    @DisplayName("getRow 获取当前行号")
    void testGetRow(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement(
                ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY);
             ResultSet rs = stmt.executeQuery("SELECT * FROM resultset_test ORDER BY id")) {
            rs.absolute(3);
            assertEquals(3, rs.getRow());
        }
    }

    @Test
    @Order(8)
    @DisplayName("beforeFirst / afterLast 定位检查")
    void testBeforeFirstAfterLast(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement(
                ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY);
             ResultSet rs = stmt.executeQuery("SELECT * FROM resultset_test ORDER BY id")) {
            rs.beforeFirst();
            assertTrue(rs.isBeforeFirst());
            assertFalse(rs.isFirst());

            rs.afterLast();
            assertTrue(rs.isAfterLast());
            assertFalse(rs.isLast());
        }
    }

    @Test
    @Order(9)
    @DisplayName("isFirst / isLast 检查")
    void testIsFirstIsLast(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement(
                ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY);
             ResultSet rs = stmt.executeQuery("SELECT * FROM resultset_test ORDER BY id")) {
            assertFalse(rs.isFirst(), "未定位时 isFirst 应为 false");
            rs.first();
            assertTrue(rs.isFirst());
            rs.last();
            assertTrue(rs.isLast());
        }
    }

    @Test
    @Order(10)
    @DisplayName("getString 获取字符串值")
    void testGetString(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM resultset_test ORDER BY id")) {
            assertTrue(rs.next());
            assertEquals("第一行", rs.getString("name"));
            assertEquals("第一行", rs.getString(2)); // 按列索引
        }
    }

    @Test
    @Order(11)
    @DisplayName("getInt 获取整数值")
    void testGetInt(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM resultset_test WHERE id = 1")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt("id"));
            assertEquals(10, rs.getInt("value"));
        }
    }

    @Test
    @Order(12)
    @DisplayName("getLong / getShort / getByte 获取数值")
    void testGetNumericTypes(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM resultset_test WHERE id = 1")) {
            assertTrue(rs.next());
            assertEquals(10L, rs.getLong("value"));
            assertEquals((short) 1, rs.getShort("status"));
        }
    }

    @Test
    @Order(13)
    @DisplayName("getDouble / getFloat 获取浮点数")
    void testGetDouble(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM resultset_test WHERE id = 1")) {
            assertTrue(rs.next());
            assertEquals(4.5, rs.getDouble("rating"), 0.01);
            assertEquals(4.5f, rs.getFloat("rating"), 0.01f);
        }
    }

    @Test
    @Order(14)
    @DisplayName("getBoolean 获取布尔值")
    void testGetBoolean(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM resultset_test WHERE id = 1")) {
            assertTrue(rs.next());
            assertTrue(rs.getBoolean("active"));
        }
    }

    @Test
    @Order(15)
    @DisplayName("getBigDecimal 获取精确小数")
    void testGetBigDecimal(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM resultset_test WHERE id = 1")) {
            assertTrue(rs.next());
            BigDecimal expected = new BigDecimal("100.50");
            assertEquals(0, expected.compareTo(rs.getBigDecimal("amount")), "amount 应等于 100.50");
        }
    }

    @Test
    @Order(16)
    @DisplayName("getDate 获取日期")
    void testGetDate(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM resultset_test WHERE id = 1")) {
            assertTrue(rs.next());
            assertEquals(Date.valueOf("2024-01-01"), rs.getDate("updated_at"));
        }
    }

    @Test
    @Order(17)
    @DisplayName("getTimestamp 获取时间戳")
    void testGetTimestamp(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM resultset_test WHERE id = 1")) {
            assertTrue(rs.next());
            Timestamp ts = rs.getTimestamp("created_at");
            assertNotNull(ts);
            assertEquals(Timestamp.valueOf("2024-01-01 10:00:00"), ts);
        }
    }

    @Test
    @Order(18)
    @DisplayName("wasNull 检查最后读取列是否为 NULL")
    void testWasNull(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT description, data FROM resultset_test WHERE id = 1")) {
            assertTrue(rs.next());
            if (isOracle()) {
                // Oracle CLOB/CLOB getString not directly supported
                Object desc = rs.getObject("description");
                assertNotNull(desc, "description 不应为 NULL");
                assertFalse(rs.wasNull(), "非 NULL 值 wasNull 应为 false");
                // data column may be NULL - skip getString for Oracle BLOB
            } else {
                rs.getString("description"); // 非 NULL
                assertFalse(rs.wasNull(), "非 NULL 值 wasNull 应为 false");
                // data 列可能为 NULL
                rs.getString("data");
            }
        }
    }

    @Test
    @Order(19)
    @DisplayName("getStatement 获取关联的 Statement")
    void testGetStatement(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM resultset_test")) {
            assertSame(stmt, rs.getStatement(), "getStatement 应返回创建此 ResultSet 的 Statement");
        }
    }

    @Test
    @Order(20)
    @DisplayName("getMetaData 获取结果集元数据")
    void testGetMetaData(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id, name, value FROM resultset_test")) {
            ResultSetMetaData meta = rs.getMetaData();
            assertNotNull(meta);
            assertEquals(3, meta.getColumnCount());
        }
    }

    @Test
    @Order(21)
    @DisplayName("getFetchSize 和 setFetchSize")
    void testFetchSize(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.setFetchSize(10);
            try (ResultSet rs = stmt.executeQuery("SELECT * FROM resultset_test")) {
                rs.setFetchSize(5);
                assertEquals(5, rs.getFetchSize());
            }
        }
    }

    @Test
    @Order(22)
    @DisplayName("getType 和 getConcurrency")
    void testTypeAndConcurrency(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement(
                ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY);
             ResultSet rs = stmt.executeQuery("SELECT * FROM resultset_test")) {
            assertEquals(ResultSet.TYPE_SCROLL_INSENSITIVE, rs.getType());
            assertEquals(ResultSet.CONCUR_READ_ONLY, rs.getConcurrency());
        }
    }

    @Test
    @Order(23)
    @DisplayName("getWarnings 和 clearWarnings")
    void testWarnings(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM resultset_test")) {
            rs.clearWarnings();
            SQLWarning warning = rs.getWarnings();
            assertNull(warning, "clearWarnings 后 getWarnings 应返回 null");
        }
    }

    @Test
    @Order(24)
    @DisplayName("close 和 isClosed")
    void testClose(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM resultset_test")) {
            assertFalse(rs.isClosed());
            rs.close();
            assertTrue(rs.isClosed());
        }
    }

    @Test
    @Order(25)
    @DisplayName("findColumn 按名称查找列索引")
    void testFindColumn(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id, name, value FROM resultset_test")) {
            assertEquals(1, rs.findColumn("id"));
            assertEquals(2, rs.findColumn("name"));
            assertEquals(3, rs.findColumn("value"));
        }
    }

    @Test
    @Order(26)
    @DisplayName("列序号和标签读取一致")
    void testColumnIndexAndLabelReadSameValue(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id, name FROM resultset_test WHERE id = 1")) {
            assertTrue(rs.next());
            assertEquals(rs.getInt(1), rs.getInt("id"));
            assertEquals(rs.getString(2), rs.getString("name"));
        }
    }

    @Test
    @Order(27)
    @DisplayName("NULL 值与 wasNull 语义")
    void testNullValueAndWasNull(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT data FROM resultset_test WHERE id = 1")) {
            assertTrue(rs.next());
            assertNull(rs.getBytes(1));
            assertTrue(rs.wasNull(), "读取 SQL NULL 后 wasNull 应为 true");
        }
    }

    @Test
    @Order(28)
    @RequiresFeature("java_time")
    @DisplayName("getObject(Class) 返回 Java LocalDate")
    void testGetObjectAsLocalDate(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT updated_at FROM resultset_test WHERE id = 1")) {
            assertTrue(rs.next());
            assertEquals(LocalDate.of(2024, 1, 1), rs.getObject(1, LocalDate.class));
        }
    }
}
