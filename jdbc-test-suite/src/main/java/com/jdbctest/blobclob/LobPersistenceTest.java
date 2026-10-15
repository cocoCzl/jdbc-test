package com.jdbctest.blobclob;

import static org.junit.jupiter.api.Assertions.*;

import com.jdbctest.extension.JdbcContext;
import com.jdbctest.extension.JdbcTestExtension;
import com.jdbctest.extension.RequiresFeature;
import com.jdbctest.extension.UseSqlScripts;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(JdbcTestExtension.class)
@Tag("integration")
@RequiresFeature({"blobs", "clobs"})
@UseSqlScripts(ddl = "lob_persistence_ddl.sql")
class LobPersistenceTest {

  private void assertContent(Connection connection, int id, String text) throws SQLException {
    try (PreparedStatement sql = connection.prepareStatement(JdbcContext.sql(
        "SELECT binary_data, text_data FROM {{lob_persistence}} WHERE id = ?"))) {
      sql.setInt(1, id);
      try (ResultSet rows = sql.executeQuery()) {
        assertTrue(rows.next());
        assertArrayEquals(text.getBytes(StandardCharsets.UTF_8), rows.getBytes(1));
        assertEquals(text, rows.getString(2));
        assertFalse(rows.next());
      }
    }
  }

  private void insertStreams(Connection connection, int id, String text) throws SQLException {
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    try (PreparedStatement sql = connection.prepareStatement(JdbcContext.sql(
        "INSERT INTO {{lob_persistence}} (id, binary_data, text_data) VALUES (?, ?, ?)"))) {
      sql.setInt(1, id);
      sql.setBinaryStream(2, new ByteArrayInputStream(bytes), bytes.length);
      sql.setCharacterStream(3, new StringReader(text), text.length());
      assertEquals(1, sql.executeUpdate());
    }
  }

  @Test
  @DisplayName("大小 LOB 流插入和更新后完整回读")
  void testStreamInsertAndUpdate(Connection connection) throws SQLException {
    connection.setAutoCommit(false);
    try {
      for (int size : new int[] {80, 20000, 300 * 1024}) {
        String initial = "A".repeat(size);
        insertStreams(connection, size, initial);
        assertContent(connection, size, initial);
        String updated = "中文\n" + "B".repeat(size);
        byte[] bytes = updated.getBytes(StandardCharsets.UTF_8);
        try (PreparedStatement sql = connection.prepareStatement(JdbcContext.sql(
            "UPDATE {{lob_persistence}} SET binary_data = ?, text_data = ? WHERE id = ?"))) {
          sql.setBinaryStream(1, new ByteArrayInputStream(bytes));
          sql.setCharacterStream(2, new StringReader(updated));
          sql.setInt(3, size);
          assertEquals(1, sql.executeUpdate());
        }
        assertContent(connection, size, updated);
      }
    } finally {
      connection.rollback();
    }
  }

  @Test
  @RequiresFeature("lob_objects")
  @DisplayName("临时 Blob/Clob 对象绑定后完整回读")
  void testTemporaryLobBinding(Connection connection) throws SQLException {
    connection.setAutoCommit(false);
    Blob blob = connection.createBlob();
    try {
      Clob clob = connection.createClob();
      try {
        String expected = "临时 LOB\n".repeat(3000);
        blob.setBytes(1, expected.getBytes(StandardCharsets.UTF_8));
        clob.setString(1, expected);
        try (PreparedStatement sql = connection.prepareStatement(JdbcContext.sql(
            "INSERT INTO {{lob_persistence}} (id, binary_data, text_data) VALUES (?, ?, ?)"))) {
          sql.setInt(1, 1);
          sql.setBlob(2, blob);
          sql.setClob(3, clob);
          assertEquals(1, sql.executeUpdate());
        }
        assertContent(connection, 1, expected);
      } finally {
        clob.free();
      }
    } finally {
      try {
        blob.free();
      } finally {
        connection.rollback();
      }
    }
  }

  @Test
  @DisplayName("LOB 写入回滚后不保留记录")
  void testLobInsertRollback(Connection connection) throws SQLException {
    connection.setAutoCommit(false);
    try {
      insertStreams(connection, 2, "rollback lob");
      assertContent(connection, 2, "rollback lob");
      connection.rollback();
      try (Connection observer = JdbcContext.getConnection();
          Statement sql = observer.createStatement();
          ResultSet rows = sql.executeQuery(JdbcContext.sql(
              "SELECT COUNT(*) FROM {{lob_persistence}} WHERE id = 2"))) {
        assertTrue(rows.next());
        assertEquals(0, rows.getInt(1));
      }
    } finally {
      connection.rollback();
    }
  }

  @Test
  @RequiresFeature("lob_locator_updates")
  @DisplayName("锁定行的 LOB 定位器修改提交后对新连接可见")
  void testLocatorUpdate(Connection connection) throws SQLException, IOException {
    connection.setAutoCommit(false);
    try {
      insertStreams(connection, 3, "ABCDE");
      connection.commit();
      try (Statement sql = connection.createStatement();
          ResultSet rows = sql.executeQuery(JdbcContext.sql(
              "SELECT binary_data, text_data FROM {{lob_persistence}} WHERE id = 3 FOR UPDATE"))) {
        assertTrue(rows.next());
        Blob blob = rows.getBlob(1);
        try {
          Clob clob = rows.getClob(2);
          try {
            blob.setBytes(2, new byte[] {'X', 'Y'});
            try (Writer stream = clob.setCharacterStream(2)) {
              stream.write("XY");
            }
          } finally {
            clob.free();
          }
        } finally {
          blob.free();
        }
      }
      connection.commit();
      try (Connection observer = JdbcContext.getConnection()) {
        assertContent(observer, 3, "AXYDE");
      }
    } finally {
      connection.rollback();
      try (Statement sql = connection.createStatement()) {
        sql.executeUpdate(JdbcContext.sql("DELETE FROM {{lob_persistence}} WHERE id = 3"));
      }
      connection.commit();
    }
  }
}
