package com.jdbctest.preparedstatement;

import static org.junit.jupiter.api.Assertions.*;

import com.jdbctest.extension.JdbcContext;
import com.jdbctest.extension.JdbcTestExtension;
import com.jdbctest.extension.UseSqlScripts;
import java.sql.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(JdbcTestExtension.class)
@Tag("integration")
@UseSqlScripts(ddl = "sql_syntax_behavior_ddl.sql")
class SqlSyntaxBehaviorTest {

  @Test
  @DisplayName("SQL 中的空白和注释不改变绑定与查询结果")
  void testWhitespaceAndComments(Connection connection) throws SQLException {
    connection.setAutoCommit(false);
    try (PreparedStatement sql = connection.prepareStatement(JdbcContext.sql(
        "INSERT\n INTO {{sql_syntax_behavior}}\n (id, content) VALUES (?, ?)"))) {
      sql.setInt(1, 1);
      sql.setString(2, "select FROM update ? 中文");
      assertEquals(1, sql.executeUpdate());
      try (PreparedStatement query = connection.prepareStatement(JdbcContext.sql(
          "select /* FROM ? WHERE */ content\nfrom {{sql_syntax_behavior}}\nwhere id = ?"))) {
        query.setInt(1, 1);
        try (ResultSet rows = query.executeQuery()) {
          assertTrue(rows.next());
          assertEquals("select FROM update ? 中文", rows.getString(1));
          assertFalse(rows.next());
        }
      }
    } finally {
      connection.rollback();
    }
  }

  @Test
  @DisplayName("字符串中的问号和引号不增加参数数量")
  void testQuestionMarkInsideLiteral(Connection connection) throws SQLException {
    connection.setAutoCommit(false);
    try (PreparedStatement sql = connection.prepareStatement(JdbcContext.sql(
        "INSERT INTO {{sql_syntax_behavior}} (id, content) VALUES (?, 'it''s ? FROM WHERE')"))) {
      sql.setInt(1, 2);
      assertEquals(1, sql.executeUpdate());
      try (Statement query = connection.createStatement();
          ResultSet rows = query.executeQuery(JdbcContext.sql(
              "SELECT content FROM {{sql_syntax_behavior}} WHERE id = 2"))) {
        assertTrue(rows.next());
        assertEquals("it's ? FROM WHERE", rows.getString(1));
        assertFalse(rows.next());
      }
    } finally {
      connection.rollback();
    }
  }

  @Test
  @DisplayName("重复执行预编译查询使用最新参数")
  void testRepeatedQueryUsesLatestParameter(Connection connection) throws SQLException {
    connection.setAutoCommit(false);
    try {
      try (PreparedStatement insert = connection.prepareStatement(JdbcContext.sql(
          "INSERT INTO {{sql_syntax_behavior}} (id, content) VALUES (?, ?)"))) {
        for (int id : new int[] {3, 4}) {
          insert.setInt(1, id);
          insert.setString(2, "row-" + id);
          assertEquals(1, insert.executeUpdate());
        }
      }
      try (PreparedStatement query = connection.prepareStatement(JdbcContext.sql(
          "SELECT content FROM {{sql_syntax_behavior}} WHERE id = ?"))) {
        for (int id : new int[] {3, 4, 3}) {
          query.setInt(1, id);
          try (ResultSet rows = query.executeQuery()) {
            assertTrue(rows.next());
            assertEquals("row-" + id, rows.getString(1));
            assertFalse(rows.next());
          }
        }
      }
    } finally {
      connection.rollback();
    }
  }
}
