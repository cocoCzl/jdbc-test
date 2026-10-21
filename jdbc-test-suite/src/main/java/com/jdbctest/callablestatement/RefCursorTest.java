package com.jdbctest.callablestatement;

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
@Tag("integration")
@RequiresFeature({"callable_statement", "ref_cursor"})
@UseSqlScripts(ddl = "ref_cursor_ddl.sql", deferred = true)
class RefCursorTest {

    @Test
    @DisplayName("REF_CURSOR 输出按标准 JDBC 接口完整读取")
    void testRefCursorOutput(Connection connection, JdbcTestExtension.Fixtures fixtures)
        throws SQLException {
        if (com.jdbctest.config.ConfigLoader.load().db.isDialect("mysql")) {
            fixtures.prepareDdl("ref_cursor_probe_ddl.sql");
            try (CallableStatement probe = connection.prepareCall(
                "{call " + JdbcContext.objects().callableName("ref_cursor_probe") + "(?)}")) {
                registerCursor(probe);
            }
            // Registration is only an API probe. Never execute this scalar OUT procedure
            // as though it provided a cursor, or count registration as a feature pass.
        }
        fixtures.prepare();
        connection.setAutoCommit(false);
        try (CallableStatement sql = connection.prepareCall(
            "{call " + JdbcContext.objects().callableName("cursor_output") + "(?)}")) {
            registerNativeCursor(sql);
            sql.execute();
            Object output = sql.getObject(1);
            assertInstanceOf(ResultSet.class, output);
            try (ResultSet rows = (ResultSet) output) {
                assertTrue(rows.next());
                assertEquals(11, rows.getInt(1));
                assertTrue(rows.next());
                assertEquals(22, rows.getInt(1));
                assertFalse(rows.next());
            }
        } finally {
            connection.rollback();
        }
    }

    static void registerNativeCursor(CallableStatement sql) throws SQLException {
        sql.registerOutParameter(1, Types.REF_CURSOR);
    }

    static void registerCursor(CallableStatement sql) throws SQLException {
        JdbcContracts.optional("CallableStatement.registerOutParameter(SQLType.REF_CURSOR)", () -> {
            sql.registerOutParameter(1, JDBCType.REF_CURSOR);
            return null;
        });
    }
}
