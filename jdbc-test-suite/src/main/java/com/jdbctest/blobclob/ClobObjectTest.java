package com.jdbctest.blobclob;

import static org.junit.jupiter.api.Assertions.*;

import com.jdbctest.extension.JdbcTestExtension;
import com.jdbctest.extension.RequiresFeature;
import java.io.*;
import java.sql.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(JdbcTestExtension.class)
@Tag("integration")
@RequiresFeature({"clobs", "lob_objects"})
class ClobObjectTest {

  @Test
  @DisplayName("Clob 局部覆盖保留前后字符")
  void testClobPartialWrite(Connection connection) throws SQLException {
    Clob clob = connection.createClob();
    try {
      assertEquals(5, clob.setString(1, "abcde"));
      assertEquals(2, clob.setString(2, "01234", 1, 2));
      assertEquals(5, clob.length());
      assertEquals("a12de", clob.getSubString(1, 5));
      assertEquals(2, clob.position("12", 1));
      assertEquals(-1, clob.position("missing", 1));
    } finally {
      clob.free();
    }
  }

  @Test
  @DisplayName("Clob 截断按字符保留中文前缀")
  void testClobTruncateMultibyteCharacters(Connection connection) throws SQLException {
    Clob clob = connection.createClob();
    try {
      clob.setString(1, "中文字符");
      assertEquals(4, clob.length());
      clob.truncate(2);
      assertEquals(2, clob.length());
      assertEquals("中文", clob.getSubString(1, 2));
      clob.truncate(0);
      assertEquals(0, clob.length());
    } finally {
      clob.free();
    }
  }

  @Test
  @DisplayName("Clob 拒绝负长度和非法位置")
  void testClobInvalidBounds(Connection connection) throws SQLException {
    Clob clob = connection.createClob();
    try {
      clob.setString(1, "ab");
      // JDBC does not define one portable outcome for truncate beyond the current length.
      assertThrows(SQLException.class, () -> clob.truncate(-1));
      assertThrows(SQLException.class, () -> clob.getSubString(0, 1));
      assertThrows(SQLException.class, () -> clob.setString(0, "x"));
      assertEquals("ab", clob.getSubString(1, 2));
    } finally {
      clob.free();
    }
  }

  @Test
  @DisplayName("Clob 释放后拒绝读取和修改")
  void testClobAfterFree(Connection connection) throws SQLException {
    Clob clob = connection.createClob();
    try {
      clob.setString(1, "ab");
    } finally {
      clob.free();
    }
    assertThrows(SQLException.class, clob::length);
    assertThrows(SQLException.class, () -> clob.getSubString(1, 1));
    assertThrows(SQLException.class, () -> clob.truncate(1));
    assertDoesNotThrow(clob::free);
  }

  @Test
  @DisplayName("Clob 流完整保留大文本和换行")
  void testClobStreamRoundTrip(Connection connection) throws SQLException, IOException {
    String expected = "中文 abc\n".repeat(20000);
    Clob clob = connection.createClob();
    try {
      try (Writer stream = clob.setCharacterStream(1)) {
        stream.write(expected);
      }
      assertEquals(expected.length(), clob.length());
      try (Reader stream = clob.getCharacterStream(); StringWriter actual = new StringWriter()) {
        stream.transferTo(actual);
        assertEquals(expected, actual.toString());
      }
    } finally {
      clob.free();
    }
  }
}
