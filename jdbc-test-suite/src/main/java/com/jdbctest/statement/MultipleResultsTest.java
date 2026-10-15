package com.jdbctest.statement;

import static org.junit.jupiter.api.Assertions.*;

import com.jdbctest.extension.JdbcContext;
import com.jdbctest.extension.JdbcTestExtension;
import com.jdbctest.extension.RequiresFeature;
import com.jdbctest.extension.UseSqlScripts;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(JdbcTestExtension.class)
@Tag("integration")
@RequiresFeature({"callable_statement", "multiple_result_sets", "multiple_result_sequences"})
@UseSqlScripts(ddl = "multiple_results_ddl.sql")
class MultipleResultsTest {

  @Test
  @DisplayName("多个结果集与更新计数完整遍历至结束")
  void testResultSequence(Connection connection) throws SQLException {
    connection.setAutoCommit(false);
    try (CallableStatement sql = connection.prepareCall(JdbcContext.sql(
        "{call {{multiple_results}}()}"))) {
      boolean result = sql.execute();
      List<Integer> actual = new ArrayList<>();
      List<ResultSet> closed = new ArrayList<>();
      boolean updated = false;
      int steps = 0;
      while (result || sql.getUpdateCount() != -1) {
        assertTrue(++steps <= 8, "Result traversal must terminate");
        if (result) {
          ResultSet rows = sql.getResultSet();
          assertNotNull(rows);
          assertTrue(rows.next());
          actual.add(rows.getInt(1));
          assertFalse(rows.next());
          closed.add(rows);
        } else {
          assertTrue(sql.getUpdateCount() >= 0);
          updated |= sql.getUpdateCount() == 1;
        }
        result = sql.getMoreResults(Statement.CLOSE_CURRENT_RESULT);
        for (ResultSet rows : closed) {
          assertTrue(rows.isClosed(), "CLOSE_CURRENT_RESULT must close preceding result sets");
        }
      }
      assertEquals(List.of(11, 22), actual);
      assertTrue(updated, "Procedure must report its one-row update");
      assertEquals(-1, sql.getUpdateCount());
      try (Statement check = connection.createStatement();
          ResultSet rows = check.executeQuery(JdbcContext.sql(
              "SELECT value FROM {{multiple_results_data}} WHERE id = 1"))) {
        assertTrue(rows.next());
        assertEquals(1, rows.getInt(1));
      }
    } finally {
      connection.rollback();
    }
  }
}
