package com.jdbctest.advancedtype;

import com.jdbctest.extension.JdbcTestExtension;
import com.jdbctest.extension.RequiresFeature;
import com.jdbctest.extension.UseSqlScripts;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;

import java.sql.*;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(JdbcTestExtension.class)
@RequiresFeature("array_type")
@UseSqlScripts(
    ddl = {"advancedtype_ddl.sql"},
    dml = {"advancedtype_dml.sql"}
)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AdvancedTypeTest {

    @Test
    @Order(1)
    @DisplayName("getArray 读取数组")
    void testGetArray(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT int_array FROM array_test WHERE id = 1");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            Array arr = rs.getArray("int_array");
            assertNotNull(arr, "getArray 应返回非空");
            // PG 返回数组元素的基础类型，不是 Types.ARRAY
            assertEquals(Types.INTEGER, arr.getBaseType(), "int_array 的元素基础类型应为 INTEGER");
        }
    }

    @Test
    @Order(2)
    @DisplayName("Array.getArray 获取 Java 数组")
    void testArrayGetArray(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT int_array FROM array_test WHERE id = 1");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            Array arr = rs.getArray("int_array");
            Integer[] values = (Integer[]) arr.getArray();
            assertNotNull(values, "getArray() 应返回非空");
            assertTrue(values.length >= 5, "int_array 应有至少 5 个元素");
            assertEquals(Integer.valueOf(1), values[0], "第一个元素应为 1");
            assertEquals(Integer.valueOf(5), values[4], "第五个元素应为 5");
        }
    }

    @Test
    @Order(3)
    @DisplayName("setArray 写入数组")
    void testSetArray(Connection conn) throws SQLException {
        Integer[] testArray = {10, 20, 30};
        Array sqlArray = conn.createArrayOf("INTEGER", testArray);

        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO array_test (name, int_array) VALUES (?, ?)")) {
            ps.setString(1, "setArray测试");
            ps.setArray(2, sqlArray);
            assertEquals(1, ps.executeUpdate());
        }

        // 验证
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT int_array FROM array_test WHERE name = 'setArray测试'");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            Array arr = rs.getArray("int_array");
            Integer[] result = (Integer[]) arr.getArray();
            assertArrayEquals(testArray, result);
        }
    }

    @Test
    @Order(4)
    @DisplayName("Array.getBaseType 获取基础类型")
    void testGetBaseType(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT int_array FROM array_test WHERE id = 1");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            Array arr = rs.getArray("int_array");
            int baseType = arr.getBaseType();
            assertEquals(Types.INTEGER, baseType, "int_array 的基础类型应为 INTEGER");
        }
    }

    @Test
    @Order(5)
    @DisplayName("Array.getBaseTypeName 获取基础类型名称")
    void testGetBaseTypeName(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT int_array FROM array_test WHERE id = 1");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            Array arr = rs.getArray("int_array");
            String typeName = arr.getBaseTypeName();
            assertNotNull(typeName, "基础类型名不应为空");
            assertTrue(typeName.contains("int") || typeName.contains("int4"),
                    "基础类型名应包含 int");
        }
    }

    @Test
    @Order(6)
    @DisplayName("数组的 getResultSet 遍历")
    void testArrayGetResultSet(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT int_array FROM array_test WHERE id = 1");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            Array arr = rs.getArray("int_array");
            try (ResultSet arrRs = arr.getResultSet()) {
                assertNotNull(arrRs, "数组 ResultSet 不应为空");
                int count = 0;
                while (arrRs.next()) {
                    count++;
                    arrRs.getInt(2);// 列1=索引, 列2=值
                }
                assertEquals(5, count, "应有 5 个元素");
            }
        }
    }

    @Test
    @Order(7)
    @DisplayName("String 数组的读写")
    void testStringArray(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT text_array FROM array_test WHERE id = 1");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            Array arr = rs.getArray("text_array");
            String[] values = (String[]) arr.getArray();
            assertNotNull(values);
            assertTrue(values.length >= 3);
            assertEquals("a", values[0]);
        }
    }

    @Test
    @Order(8)
    @DisplayName("空数组的处理")
    void testEmptyArray(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT int_array FROM array_test WHERE id = 2");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            Array arr = rs.getArray("int_array");
            assertNotNull(arr, "空数组不应为 null");
            Integer[] values = (Integer[]) arr.getArray();
            assertNotNull(values);
            assertEquals(0, values.length, "空数组长度应为 0");
        }
    }
}
