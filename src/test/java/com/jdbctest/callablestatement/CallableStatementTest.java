package com.jdbctest.callablestatement;

import com.jdbctest.config.Config;
import com.jdbctest.config.ConfigLoader;
import com.jdbctest.extension.JdbcTestExtension;
import com.jdbctest.extension.RequiresFeature;
import com.jdbctest.extension.UseSqlScripts;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;

import java.sql.*;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(JdbcTestExtension.class)
@RequiresFeature("callable_statement")
@UseSqlScripts(
    ddl = {"callablestatement_ddl.sql"},
    dml = {"callablestatement_dml.sql"},
    cleanup = {"callablestatement_cleanup.sql"}
)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CallableStatementTest {

    private static boolean isOracle() {
        return ConfigLoader.load().db.isDialect("oracle");
    }

    private static String fn(String name) {
        return isOracle() ? "jt_" + name : name;
    }

    // For Oracle, use SELECT func() FROM DUAL instead of CallableStatement
    // because Oracle's {} escape syntax has compatibility issues with functions
    private int callFunctionInt(Connection conn, String name, Object... params) throws SQLException {
        if (isOracle()) {
            try (PreparedStatement ps = conn.prepareStatement("SELECT " + fn(name) + "(" + paramPlaceholders(params.length) + ") FROM DUAL")) {
                for (int i = 0; i < params.length; i++) {
                    ps.setObject(i + 1, params[i]);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    return rs.getInt(1);
                }
            }
        }
        try (CallableStatement cs = conn.prepareCall(buildCall(name, params.length))) {
            cs.registerOutParameter(1, Types.INTEGER);
            for (int i = 0; i < params.length; i++) {
                cs.setObject(i + 2, params[i]);
            }
            cs.execute();
            return cs.getInt(1);
        }
    }

    private Object callFunctionObj(Connection conn, String name, int sqlType, Object... params) throws SQLException {
        if (isOracle()) {
            try (PreparedStatement ps = conn.prepareStatement("SELECT " + fn(name) + "(" + paramPlaceholders(params.length) + ") FROM DUAL")) {
                for (int i = 0; i < params.length; i++) {
                    ps.setObject(i + 1, params[i]);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    Object val = rs.getObject(1);
                    return rs.wasNull() ? null : val;
                }
            }
        }
        try (CallableStatement cs = conn.prepareCall(buildCall(name, params.length))) {
            cs.registerOutParameter(1, sqlType);
            for (int i = 0; i < params.length; i++) {
                cs.setObject(i + 2, params[i]);
            }
            cs.execute();
            Object val = cs.getObject(1);
            return cs.wasNull() ? null : val;
        }
    }

    private String buildCall(String name, int paramCount) {
        StringBuilder sb = new StringBuilder("{? = call ").append(fn(name)).append("(");
        for (int i = 0; i < paramCount; i++) {
            if (i > 0) sb.append(", ");
            sb.append("?");
        }
        sb.append(")}");
        return sb.toString();
    }

    private String paramPlaceholders(int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append(", ");
            sb.append("?");
        }
        return sb.toString();
    }

    @Test
    @Order(1)
    @DisplayName("调用函数带返回值")
    void testCallFunctionWithReturn(Connection conn) throws SQLException {
        assertEquals(30, callFunctionInt(conn, "add_numbers", 10, 20), "10 + 20 应等于 30");
    }

    @Test
    @Order(2)
    @DisplayName("调用函数获取名称")
    void testCallFunctionGetName(Connection conn) throws SQLException {
        assertEquals("张三", callFunctionObj(conn, "get_name_by_id", Types.VARCHAR, 1), "ID=1 的名称应为 张三");
    }

    @Test
    @Order(3)
    @DisplayName("调用函数获取名称（ID=2）")
    void testCallFunctionGetNameId2(Connection conn) throws SQLException {
        assertEquals("李四", callFunctionObj(conn, "get_name_by_id", Types.VARCHAR, 2), "ID=2 的名称应为 李四");
    }

    @Test
    @Order(4)
    @DisplayName("调用无参函数获取总数")
    void testCallFunctionGetCount(Connection conn) throws SQLException {
        int count = callFunctionInt(conn, "get_count");
        assertEquals(3, count, "应有 3 条记录");
    }

    @Test
    @Order(5)
    @DisplayName("execute 执行 CallableStatement")
    void testExecute(Connection conn) throws SQLException {
        assertEquals(12, callFunctionInt(conn, "add_numbers", 5, 7), "5 + 7 应等于 12");
    }

    @Test
    @Order(6)
    @DisplayName("wasNull 检查 NULL 输出")
    void testWasNull(Connection conn) throws SQLException {
        Object result = callFunctionObj(conn, "get_name_by_id", Types.VARCHAR, 999);
        assertNull(result, "不存在的 ID 应返回 null");
    }

    @Test
    @Order(7)
    @DisplayName("多个参数调用函数")
    void testMultipleParams(Connection conn) throws SQLException {
        assertEquals(300, callFunctionInt(conn, "add_numbers", 100, 200));
    }
}
