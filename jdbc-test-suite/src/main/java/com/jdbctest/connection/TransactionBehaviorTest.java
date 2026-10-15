package com.jdbctest.connection;

import static org.junit.jupiter.api.Assertions.*;

import com.jdbctest.extension.JdbcContext;
import com.jdbctest.extension.JdbcTestExtension;
import com.jdbctest.extension.UseSqlScripts;
import java.sql.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;

/** Standard JDBC transaction regressions with independent connection readback. */
@ExtendWith(JdbcTestExtension.class)
@Tag("integration")
@UseSqlScripts(ddl = "transaction_behavior_ddl.sql")
class TransactionBehaviorTest {

  private void insert(Connection connection, int id) throws SQLException {
    try (PreparedStatement sql = connection.prepareStatement(JdbcContext.sql(
        "INSERT INTO {{transaction_behavior}} (id, value) VALUES (?, ?)"))) {
      sql.setInt(1, id);
      sql.setInt(2, id * 10);
      assertEquals(1, sql.executeUpdate());
    }
  }

  private void assertVisible(int id, int expected) throws SQLException {
    try (Connection observer = JdbcContext.getConnection();
        PreparedStatement sql = observer.prepareStatement(JdbcContext.sql(
            "SELECT value FROM {{transaction_behavior}} WHERE id = ?"))) {
      sql.setInt(1, id);
      try (ResultSet rows = sql.executeQuery()) {
        if (expected == 0) {
          assertFalse(rows.next(), "Rolled-back row must not be visible to a new connection");
        } else {
          assertTrue(rows.next());
          assertEquals(id * 10, rows.getInt(1));
          assertFalse(rows.next());
        }
      }
    }
  }

  private void delete(Connection connection, int... ids) throws SQLException {
    connection.rollback();
    try (PreparedStatement sql = connection.prepareStatement(JdbcContext.sql(
        "DELETE FROM {{transaction_behavior}} WHERE id = ?"))) {
      for (int id : ids) {
        sql.setInt(1, id);
        sql.executeUpdate();
      }
    }
    connection.commit();
  }

  @Test
  @DisplayName("提交结果对新连接可见")
  void testCommitVisibleToNewConnection(Connection connection) throws SQLException {
    connection.setAutoCommit(false);
    try {
      insert(connection, 101);
      connection.commit();
      assertVisible(101, 1);
    } finally {
      delete(connection, 101);
    }
  }

  @Test
  @DisplayName("回滚并关闭后新连接看不到写入")
  void testRollbackAndClose(Connection connection) throws SQLException {
    connection.setAutoCommit(false);
    try {
      insert(connection, 102);
      connection.rollback();
    } finally {
      connection.rollback();
    }
    connection.close();
    assertVisible(102, 0);
  }

  @Test
  @DisplayName("同一连接的连续事务相互独立")
  void testConsecutiveTransactions(Connection connection) throws SQLException {
    connection.setAutoCommit(false);
    try {
      insert(connection, 103);
      connection.commit();
      insert(connection, 104);
      connection.rollback();
      assertVisible(103, 1);
      assertVisible(104, 0);
    } finally {
      delete(connection, 103, 104);
    }
  }

  @Test
  @DisplayName("回滚后可开始并提交新的事务")
  void testWriteAfterRollback(Connection connection) throws SQLException {
    connection.setAutoCommit(false);
    try {
      insert(connection, 105);
      connection.rollback();
      insert(connection, 106);
      connection.commit();
      assertVisible(105, 0);
      assertVisible(106, 1);
    } finally {
      delete(connection, 105, 106);
    }
  }

  @Test
  @DisplayName("开启自动提交会提交当前事务")
  void testEnableAutoCommitCommitsPendingWork(Connection connection) throws SQLException {
    connection.setAutoCommit(false);
    try {
      insert(connection, 107);
      connection.setAutoCommit(true);
      assertTrue(connection.getAutoCommit());
      assertVisible(107, 1);
    } finally {
      connection.setAutoCommit(false);
      delete(connection, 107);
    }
  }

  @Test
  @DisplayName("多次写入和更新可整体回滚")
  void testMultipleOperationsRollback(Connection connection) throws SQLException {
    connection.setAutoCommit(false);
    try {
      insert(connection, 108);
      insert(connection, 109);
      try (Statement sql = connection.createStatement()) {
        assertEquals(2, sql.executeUpdate(JdbcContext.sql(
            "UPDATE {{transaction_behavior}} SET value = 0 WHERE id IN (108, 109)")));
      }
      connection.rollback();
      assertVisible(108, 0);
      assertVisible(109, 0);
    } finally {
      connection.rollback();
    }
  }
}
