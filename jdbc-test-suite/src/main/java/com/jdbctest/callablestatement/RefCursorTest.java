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
@RequiresFeature({"callable_statement", "ref_cursor"})
@UseSqlScripts(ddl = "ref_cursor_ddl.sql")
class RefCursorTest {

  @Test
  @DisplayName("REF_CURSOR 输出按标准 JDBC 接口完整读取")
  void testRefCursorOutput(Connection connection) throws SQLException {
    try (CallableStatement sql = connection.prepareCall(JdbcContext.sql(
        "{call {{cursor_output}}(?)}"))) {
      sql.registerOutParameter(1, Types.REF_CURSOR);
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
    }
  }
}
