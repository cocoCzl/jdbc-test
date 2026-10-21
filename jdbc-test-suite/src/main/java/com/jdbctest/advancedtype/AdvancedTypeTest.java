package com.jdbctest.advancedtype;

import static org.junit.jupiter.api.Assertions.*;

import com.jdbctest.extension.JdbcContext;
import com.jdbctest.extension.JdbcContracts;
import com.jdbctest.extension.JdbcTestExtension;
import com.jdbctest.extension.RequiresFeature;
import com.jdbctest.extension.UseSqlScripts;
import java.sql.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(JdbcTestExtension.class)
@org.junit.jupiter.api.Tag("integration")
@RequiresFeature("array_type")
@UseSqlScripts(
    ddl = {"advancedtype_ddl.sql"},
    dml = {"advancedtype_dml.sql"},
    checks = "SELECT int_array, text_array FROM {{array_test}} WHERE 1 = 0",
    deferred = true
)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AdvancedTypeTest {

    @Test
    @Order(1)
    @DisplayName("getArray 读取数组")
    void testGetArray(Connection conn, JdbcTestExtension.Fixtures fixtures)
        throws SQLException {
        observeGetterThenPrepare(conn, fixtures,
            com.jdbctest.config.ConfigLoader.load().db.isDialect("oracle"));
        try (PreparedStatement ps = conn.prepareStatement(
            JdbcContext.sql("SELECT int_array FROM {{array_test}} WHERE id = 1"));
            ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            Array arr = readArray(rs, "int_array");
            assertNotNull(arr, "getArray 应返回非空");
            assertNumericBaseType(arr);
        }
    }

    @Test
    @Order(2)
    @DisplayName("Array.getArray 获取 Java 数组")
    void testArrayGetArray(Connection conn, JdbcTestExtension.Fixtures fixtures)
        throws SQLException {
        observeGetterThenPrepare(conn, fixtures,
            com.jdbctest.config.ConfigLoader.load().db.isDialect("oracle"));
        try (PreparedStatement ps = conn.prepareStatement(
            JdbcContext.sql("SELECT int_array FROM {{array_test}} WHERE id = 1"));
            ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            Array arr = readArray(rs, "int_array");
            int[] values = integerElements(arr);
            assertNotNull(values, "getArray() 应返回非空");
            assertTrue(values.length >= 5, "int_array 应有至少 5 个元素");
            assertEquals(1, values[0], "第一个元素应为 1");
            assertEquals(5, values[4], "第五个元素应为 5");
        }
    }

    @Test
    @Order(3)
    @DisplayName("setArray 写入数组")
    @UseSqlScripts(ddl = "advancedtype_ddl.sql", dml = "advancedtype_dml.sql",
        checks = "SELECT int_array, text_array FROM {{array_test}} WHERE 1 = 0", deferred = true)
    void testSetArray(Connection conn, JdbcTestExtension.Fixtures fixtures) throws SQLException {
        Integer[] testArray = {10, 20, 30};
        Array sqlArray = JdbcContracts.optional("Connection.createArrayOf",
            () -> conn.createArrayOf("INTEGER", testArray));
        fixtures.prepare();

        try (PreparedStatement ps = conn.prepareStatement(
            JdbcContext.sql("INSERT INTO {{array_test}} (name, int_array) VALUES (?, ?)"))) {
            ps.setString(1, "setArray测试");
            JdbcContracts.optional("PreparedStatement.setArray", () -> {
                ps.setArray(2, sqlArray);
                return null;
            });
            assertEquals(1, ps.executeUpdate());
        }

        // 验证
        try (PreparedStatement ps = conn.prepareStatement(
            JdbcContext.sql("SELECT int_array FROM {{array_test}} WHERE name = 'setArray测试'"));
            ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            Array arr = readArray(rs, "int_array");
            assertArrayEquals(new int[] {10, 20, 30}, integerElements(arr));
        }
    }

    @Test
    @Order(4)
    @DisplayName("Array.getBaseType 获取基础类型")
    void testGetBaseType(Connection conn, JdbcTestExtension.Fixtures fixtures)
        throws SQLException {
        observeGetterThenPrepare(conn, fixtures,
            com.jdbctest.config.ConfigLoader.load().db.isDialect("oracle"));
        try (PreparedStatement ps = conn.prepareStatement(
            JdbcContext.sql("SELECT int_array FROM {{array_test}} WHERE id = 1"));
            ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            Array arr = readArray(rs, "int_array");
            assertNumericBaseType(arr);
        }
    }

    @Test
    @Order(5)
    @DisplayName("Array.getBaseTypeName 获取基础类型名称")
    void testGetBaseTypeName(Connection conn, JdbcTestExtension.Fixtures fixtures)
        throws SQLException {
        observeGetterThenPrepare(conn, fixtures,
            com.jdbctest.config.ConfigLoader.load().db.isDialect("oracle"));
        try (PreparedStatement ps = conn.prepareStatement(
            JdbcContext.sql("SELECT int_array FROM {{array_test}} WHERE id = 1"));
            ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            Array arr = readArray(rs, "int_array");
            String typeName = JdbcContracts.optional("Array.getBaseTypeName", arr::getBaseTypeName);
            assertNotNull(typeName, "基础类型名不应为空");
            assertFalse(typeName.isBlank(), "基础类型名应为数据库实际类型名");
            assertNumericBaseType(arr);
        }
    }

    @Test
    @Order(6)
    @DisplayName("数组的 getResultSet 遍历")
    void testArrayGetResultSet(Connection conn, JdbcTestExtension.Fixtures fixtures)
        throws SQLException {
        observeGetterThenPrepare(conn, fixtures,
            com.jdbctest.config.ConfigLoader.load().db.isDialect("oracle"));
        try (PreparedStatement ps = conn.prepareStatement(
            JdbcContext.sql("SELECT int_array FROM {{array_test}} WHERE id = 1"));
            ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            Array arr = readArray(rs, "int_array");
            try (ResultSet arrRs = JdbcContracts.optional("Array.getResultSet", arr::getResultSet)) {
                assertNotNull(arrRs, "数组 ResultSet 不应为空");
                int count = 0;
                while (arrRs.next()) {
                    count++;
                    assertEquals(count, arrRs.getInt(1), "数组索引从 1 开始");
                    assertEquals(count, arrRs.getInt(2), "数组元素顺序和值应保留");
                }
                assertEquals(5, count, "应有 5 个元素");
            }
        }
    }

    @Test
    @Order(7)
    @DisplayName("String 数组的读写")
    void testStringArray(Connection conn, JdbcTestExtension.Fixtures fixtures)
        throws SQLException {
        observeGetterThenPrepare(conn, fixtures,
            com.jdbctest.config.ConfigLoader.load().db.isDialect("oracle"));
        try (PreparedStatement ps = conn.prepareStatement(
            JdbcContext.sql("SELECT text_array FROM {{array_test}} WHERE id = 1"));
            ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            Array arr = readArray(rs, "text_array");
            Object values = JdbcContracts.optional("Array.getArray", arr::getArray);
            assertNotNull(values);
            assertEquals(3, java.lang.reflect.Array.getLength(values));
            assertEquals("a", java.lang.reflect.Array.get(values, 0));
            assertEquals("b", java.lang.reflect.Array.get(values, 1));
            assertEquals("c", java.lang.reflect.Array.get(values, 2));
        }
    }

    @Test
    @Order(8)
    @DisplayName("空数组的处理")
    void testEmptyArray(Connection conn, JdbcTestExtension.Fixtures fixtures)
        throws SQLException {
        observeGetterThenPrepare(conn, fixtures,
            com.jdbctest.config.ConfigLoader.load().db.isDialect("oracle"));
        try (PreparedStatement ps = conn.prepareStatement(
            JdbcContext.sql("SELECT int_array FROM {{array_test}} WHERE id = 2"));
            ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            Array arr = readArray(rs, "int_array");
            assertNotNull(arr, "空数组不应为 null");
            int[] values = integerElements(arr);
            assertNotNull(values);
            assertEquals(0, values.length, "空数组长度应为 0");
        }
    }

    static void observeGetterThenPrepare(Connection connection, JdbcTestExtension.Fixtures fixtures,
        boolean oracle) throws SQLException {
        try (Statement sql = connection.createStatement();
            ResultSet rows = sql.executeQuery(oracle ? "SELECT NULL FROM DUAL" : "SELECT NULL")) {
            assertTrue(rows.next());
            assertNull(JdbcContracts.optional("ResultSet.getArray", () -> rows.getArray(1)),
                "SQL NULL must remain null; this probe does not establish SQL ARRAY support");
        }
        fixtures.prepare();
    }

    private static Array readArray(ResultSet rows, String column) throws SQLException {
        Array value = JdbcContracts.optional("ResultSet.getArray", () -> rows.getArray(column));
        assertNotNull(value, "SQL ARRAY must not be null");
        return value;
    }

    private static void assertNumericBaseType(Array value) throws SQLException {
        int type = JdbcContracts.optional("Array.getBaseType", value::getBaseType);
        assertTrue(java.util.Set.of(Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT,
            Types.NUMERIC, Types.DECIMAL).contains(type), "Fixture elements must be numeric");
    }

    static int[] integerElements(Array value) throws SQLException {
        Object elements = JdbcContracts.optional("Array.getArray", value::getArray);
        assertNotNull(elements);
        assertTrue(elements.getClass().isArray(), "Array.getArray must return a Java array");
        int[] result = new int[java.lang.reflect.Array.getLength(elements)];
        for (int index = 0; index < result.length; index++) {
            Object element = java.lang.reflect.Array.get(elements, index);
            assertInstanceOf(Number.class, element, "Integer fixture must retain numeric elements");
            result[index] = assertDoesNotThrow(
                () -> new java.math.BigDecimal(element.toString()).intValueExact(),
                "Integer fixture must retain exact integral values");
        }
        return result;
    }
}
