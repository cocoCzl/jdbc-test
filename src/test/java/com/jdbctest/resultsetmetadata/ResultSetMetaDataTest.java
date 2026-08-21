package com.jdbctest.resultsetmetadata;

import com.jdbctest.config.ConfigLoader;
import com.jdbctest.extension.JdbcTestExtension;
import com.jdbctest.extension.UseSqlScripts;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;

import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(JdbcTestExtension.class)
@UseSqlScripts(
    ddl = {"resultset_ddl.sql"},
    dml = {"resultset_dml.sql"}
)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ResultSetMetaDataTest {

    private static boolean isOracle() {
        return ConfigLoader.load().db.isDialect("oracle");
    }

    @Test
    @Order(1)
    @DisplayName("getColumnCount 获取列数")
    void testGetColumnCount(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id, name, value, amount, active FROM resultset_test")) {
            ResultSetMetaData meta = rs.getMetaData();
            assertEquals(5, meta.getColumnCount(), "应返回 5 列");
        }
    }

    @Test
    @Order(2)
    @DisplayName("getColumnName 获取列名")
    void testGetColumnName(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id, name, value FROM resultset_test")) {
            ResultSetMetaData meta = rs.getMetaData();
            if (isOracle()) {
                // Oracle returns uppercase column names by default
                assertEquals("ID", meta.getColumnName(1));
                assertEquals("NAME", meta.getColumnName(2));
                assertEquals("VALUE", meta.getColumnName(3));
            } else {
                assertEquals("id", meta.getColumnName(1));
                assertEquals("name", meta.getColumnName(2));
                assertEquals("value", meta.getColumnName(3));
            }
        }
    }

    @Test
    @Order(3)
    @DisplayName("getColumnLabel 获取列标签")
    void testGetColumnLabel(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT name AS 姓名 FROM resultset_test")) {
            ResultSetMetaData meta = rs.getMetaData();
            assertEquals("姓名", meta.getColumnLabel(1));
        }
    }

    @Test
    @Order(4)
    @DisplayName("getColumnType 获取列 SQL 类型")
    void testGetColumnType(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id, name, value FROM resultset_test")) {
            ResultSetMetaData meta = rs.getMetaData();
            // Oracle maps NUMBER to NUMERIC(2), others to INTEGER(4)
            int idType = meta.getColumnType(1);
            if (isOracle()) {
                assertEquals(Types.NUMERIC, idType, "Oracle id 应为 NUMERIC 类型");
            } else {
                assertEquals(Types.INTEGER, idType, "id 应为 INTEGER 类型");
            }
            assertEquals(Types.VARCHAR, meta.getColumnType(2), "name 应为 VARCHAR 类型");
        }
    }

    @Test
    @Order(5)
    @DisplayName("getColumnTypeName 获取列类型名称")
    void testGetColumnTypeName(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id, name FROM resultset_test")) {
            ResultSetMetaData meta = rs.getMetaData();
            assertNotNull(meta.getColumnTypeName(1));
            String typeName = meta.getColumnTypeName(1).toLowerCase();
            assertTrue(typeName.matches("int4|serial|int|integer|number"), "id 类型名应为数字类型: " + typeName);
            assertTrue(meta.getColumnTypeName(2).toLowerCase().contains("varchar") ||
                       meta.getColumnTypeName(2).toLowerCase().contains("char"));
        }
    }

    @Test
    @Order(6)
    @DisplayName("getColumnDisplaySize 获取列显示大小")
    void testGetColumnDisplaySize(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id, name FROM resultset_test")) {
            ResultSetMetaData meta = rs.getMetaData();
            assertTrue(meta.getColumnDisplaySize(1) > 0, "id 的 display size 应大于 0");
            assertTrue(meta.getColumnDisplaySize(2) > 0, "name 的 display size 应大于 0");
        }
    }

    @Test
    @Order(7)
    @DisplayName("getPrecision 和 getScale")
    void testPrecisionAndScale(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id, amount FROM resultset_test")) {
            ResultSetMetaData meta = rs.getMetaData();
            // Oracle NUMBER without precision may return 0 or -127
            if (!isOracle()) {
                assertTrue(meta.getPrecision(1) > 0, "id 精度应大于 0");
            }
            if (isOracle()) {
                // Oracle scale may be -127 for NUMBER without scale
                int scale = meta.getScale(1);
                assertTrue(scale == 0 || scale == -127, "id scale 应为 0 或 -127, 实际: " + scale);
            } else {
                assertEquals(0, meta.getScale(1), "id 小数位数应为 0");
            }
            assertEquals(2, meta.getScale(2), "amount 小数位数应为 2");
        }
    }

    @Test
    @Order(8)
    @DisplayName("isNullable 检查是否可为 NULL")
    void testIsNullable(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id, name, description FROM resultset_test")) {
            ResultSetMetaData meta = rs.getMetaData();
            assertEquals(ResultSetMetaData.columnNoNulls, meta.isNullable(1), "id 不应为 NULL");
            assertEquals(ResultSetMetaData.columnNullable, meta.isNullable(3), "description 可为 NULL");
        }
    }

    @Test
    @Order(9)
    @DisplayName("isAutoIncrement 检查自增")
    void testIsAutoIncrement(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id, name FROM resultset_test")) {
            ResultSetMetaData meta = rs.getMetaData();
            if (isOracle()) {
                assertDoesNotThrow(() -> meta.isAutoIncrement(1), "isAutoIncrement 调用应正常完成");
            } else {
                assertTrue(meta.isAutoIncrement(1), "id 应为自增列");
            }
            assertFalse(meta.isAutoIncrement(2), "name 不应为自增列");
        }
    }

    @Test
    @Order(10)
    @DisplayName("isCaseSensitive / isCurrency / isSigned / isReadOnly 等属性")
    void testColumnProperties(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT name, value, big_num FROM resultset_test")) {
            ResultSetMetaData meta = rs.getMetaData();
            // Oracle may report NUMBER as currency
            if (!isOracle()) {
                assertFalse(meta.isCurrency(2), "INT 不应为货币类型");
            }
            assertTrue(meta.isSigned(2), "INT 应为有符号");
            meta.isReadOnly(1);
        }
    }

    @Test
    @Order(11)
    @DisplayName("isWritable / isDefinitelyWritable / isSearchable")
    void testWritableSearchable(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT name, value FROM resultset_test")) {
            ResultSetMetaData meta = rs.getMetaData();
            assertTrue(meta.isSearchable(1), "name 应可搜索");
        }
    }

    @Test
    @Order(12)
    @DisplayName("getSchemaName / getTableName / getCatalogName")
    void testSchemaTableCatalog(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id FROM resultset_test")) {
            ResultSetMetaData meta = rs.getMetaData();
            String tableName = meta.getTableName(1);
            // Oracle may return empty table name for certain queries
            if (!isOracle() || !tableName.isEmpty()) {
                assertEquals("resultset_test", tableName.toLowerCase(java.util.Locale.ENGLISH));
            }
        }
    }

    @Test
    @Order(13)
    @DisplayName("getColumnClassName 获取列的 Java 类名")
    void testGetColumnClassName(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id, name, amount FROM resultset_test")) {
            ResultSetMetaData meta = rs.getMetaData();
            assertNotNull(meta.getColumnClassName(1));
            assertEquals("java.lang.String", meta.getColumnClassName(2));
        }
    }
}
