package com.jdbctest.callablestatement;

import static org.junit.jupiter.api.Assertions.*;

import com.jdbctest.extension.JdbcContext;
import com.jdbctest.extension.JdbcTestExtension;
import com.jdbctest.extension.RequiresFeature;
import com.jdbctest.extension.UseSqlScripts;
import java.sql.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(JdbcTestExtension.class)
@Tag("integration")
@RequiresFeature({"callable_statement", "callable_inout"})
@UseSqlScripts(ddl = "procedure_behavior_ddl.sql")
class ProcedureBehaviorTest {

    private CallableStatement prepare(Connection connection) throws SQLException {
        return connection.prepareCall(
            "{call " + JdbcContext.objects().callableName("procedure_inout")
                + "(?, ?, ?)}");
    }

    @Test
    @DisplayName("存储过程 IN、OUT、INOUT 参数实际执行")
    void testInOutAndInOut(Connection connection) throws SQLException {
        try (CallableStatement sql = prepare(connection)) {
            sql.setInt(1, 7);
            sql.setInt(2, 10);
            sql.registerOutParameter(2, Types.INTEGER);
            sql.registerOutParameter(3, Types.INTEGER);
            sql.execute();
            assertEquals(17, sql.getInt(2));
            assertFalse(sql.wasNull());
            assertEquals(14, sql.getInt(3));
            assertFalse(sql.wasNull());
        }
    }

    @Test
    @DisplayName("存储过程 NULL 输出与 wasNull 一致")
    void testNullOutput(Connection connection) throws SQLException {
        try (CallableStatement sql = prepare(connection)) {
            sql.setNull(1, Types.INTEGER);
            sql.setInt(2, 10);
            sql.registerOutParameter(2, Types.INTEGER);
            sql.registerOutParameter(3, Types.INTEGER);
            sql.execute();
            assertEquals(0, sql.getInt(2));
            assertTrue(sql.wasNull());
            assertNull(sql.getObject(3));
            assertTrue(sql.wasNull());
        }
    }

    @Test
    @DisplayName("同一存储过程语句重复绑定不会复用旧参数")
    void testRepeatedBinding(Connection connection) throws SQLException {
        try (CallableStatement sql = prepare(connection)) {
            sql.registerOutParameter(2, Types.INTEGER);
            sql.registerOutParameter(3, Types.INTEGER);
            for (int value : new int[] {3, 9}) {
                sql.setInt(1, value);
                sql.setInt(2, 20);
                sql.execute();
                assertEquals(20 + value, sql.getInt(2));
                assertEquals(value * 2, sql.getInt(3));
            }
        }
    }

    @Test
    @DisplayName("getProcedureColumns 返回正确参数方向")
    void testProcedureParameterMetadata(Connection connection) throws SQLException {
        var config = com.jdbctest.config.ConfigLoader.load();
        String catalog =
            config.db.isDialect("mysql") ? config.namespace.name : connection.getCatalog();
        String schema = config.db.isDialect("mysql") ? null : config.namespace.name;
        String escape = connection.getMetaData().getSearchStringEscape();
        String name = JdbcContext.objectName("procedure_inout");
        String pattern =
            escape == null || escape.isEmpty() ? name : name.replace("_", escape + "_");
        java.util.Map<Integer, Integer> modes = new java.util.HashMap<>();
        try (ResultSet rows = connection.getMetaData().getProcedureColumns(catalog, schema, pattern,
            "%")) {
            while (rows.next()) {
                int mode = rows.getInt("COLUMN_TYPE");
                if (mode != DatabaseMetaData.procedureColumnReturn
                    && mode != DatabaseMetaData.procedureColumnResult) {
                    modes.put(rows.getInt("ORDINAL_POSITION"), mode);
                }
            }
        }
        int outputMode = config.db.isDialect("sqlserver") ? DatabaseMetaData.procedureColumnInOut
            : DatabaseMetaData.procedureColumnOut;
        assertEquals(java.util.Map.of(1, DatabaseMetaData.procedureColumnIn,
            2, DatabaseMetaData.procedureColumnInOut, 3, outputMode), modes);
    }
}
