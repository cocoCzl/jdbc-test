package com.jdbctest.parametermetadata;

import com.jdbctest.extension.JdbcTestExtension;
import com.jdbctest.extension.RequiresFeature;
import com.jdbctest.extension.UseSqlScripts;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;

import java.sql.*;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(JdbcTestExtension.class)
@RequiresFeature("parameter_metadata")
@UseSqlScripts(
    ddl = {"resultset_ddl.sql"},
    dml = {"resultset_dml.sql"}
)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ParameterMetaDataTest {

    @Test
    @Order(1)
    @DisplayName("getParameterCount 获取参数数量")
    void testGetParameterCount(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM resultset_test WHERE name = ? AND value = ? AND active = ?")) {
            ParameterMetaData meta = ps.getParameterMetaData();
            assertEquals(3, meta.getParameterCount(), "应有 3 个参数");
        }
    }

    @Test
    @Order(2)
    @DisplayName("getParameterType 获取参数 SQL 类型")
    void testGetParameterType(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM resultset_test WHERE name = ? AND value = ?")) {
            ParameterMetaData meta = ps.getParameterMetaData();
            assertTrue(meta.getParameterType(1) == Types.VARCHAR || meta.getParameterType(1) == Types.OTHER,
                    "第一个参数类型应为 VARCHAR 或 OTHER");
        }
    }

    @Test
    @Order(3)
    @DisplayName("getParameterTypeName 获取参数类型名称")
    void testGetParameterTypeName(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM resultset_test WHERE name = ? AND value = ?")) {
            ParameterMetaData meta = ps.getParameterMetaData();
            // PG 可能返回类型名或 null
            String typeName = meta.getParameterTypeName(1);
            if (typeName != null) {
                assertFalse(typeName.isEmpty());
            }
        }
    }

    @Test
    @Order(4)
    @DisplayName("getPrecision 和 getScale 获取参数精度")
    void testPrecisionAndScale(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM resultset_test WHERE name = ? AND amount = ?")) {
            ParameterMetaData meta = ps.getParameterMetaData();
            // 精度和刻度取决于驱动实现
            assertTrue(meta.getPrecision(1) >= 0, "精度应 >= 0");
            assertTrue(meta.getScale(1) >= 0, "刻度应 >= 0");
        }
    }

    @Test
    @Order(5)
    @DisplayName("isNullable 检查参数是否可为 NULL")
    void testIsNullable(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM resultset_test WHERE name = ? AND value = ?")) {
            ParameterMetaData meta = ps.getParameterMetaData();
            int nullable = meta.isNullable(1);
            assertTrue(
                    nullable == ParameterMetaData.parameterNoNulls ||
                            nullable == ParameterMetaData.parameterNullable ||
                            nullable == ParameterMetaData.parameterNullableUnknown,
                    "isNullable 应返回有效值");
        }
    }

    @Test
    @Order(6)
    @DisplayName("isSigned 检查参数是否为有符号")
    void testIsSigned(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM resultset_test WHERE value = ? AND name = ?")) {
            ParameterMetaData meta = ps.getParameterMetaData();
            // value 参数为数值类型，但驱动可能返回有符号/无符号信息
            meta.isSigned(1); // 不应抛异常
            meta.isSigned(2); // 不应抛异常
        }
    }

    @Test
    @Order(7)
    @DisplayName("getParameterClassName 获取参数 Java 类名")
    void testGetParameterClassName(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM resultset_test WHERE name = ? AND value = ?")) {
            ParameterMetaData meta = ps.getParameterMetaData();
            String className = meta.getParameterClassName(1);
            if (className != null) {
                assertFalse(className.isEmpty());
            }
        }
    }

    @Test
    @Order(8)
    @DisplayName("getParameterMode 获取参数模式")
    void testGetParameterMode(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM resultset_test WHERE name = ?")) {
            ParameterMetaData meta = ps.getParameterMetaData();
            int mode = meta.getParameterMode(1);
            assertEquals(ParameterMetaData.parameterModeIn, mode, "PreparedStatement 参数模式应为 IN");
        }
    }
}
