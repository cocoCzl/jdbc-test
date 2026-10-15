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
@RequiresFeature({"blobs", "lob_objects"})
class BlobObjectTest {

  @Test
  @DisplayName("Blob 局部写入使用 JDBC 的一基位置和源数组偏移")
  void testBlobPartialWrite(Connection connection) throws SQLException {
    Blob blob = connection.createBlob();
    try {
      assertEquals(5, blob.setBytes(1, new byte[] {1, 2, 3, 4, 5}));
      assertEquals(2, blob.setBytes(2, new byte[] {8, 9, 10, 11}, 1, 2));
      assertEquals(5, blob.length());
      assertArrayEquals(new byte[] {1, 9, 10, 4, 5}, blob.getBytes(1, 5));
      assertEquals(2, blob.position(new byte[] {9, 10}, 1));
      assertEquals(-1, blob.position(new byte[] {99}, 1));
    } finally {
      blob.free();
    }
  }

  @Test
  @DisplayName("Blob 截断保留前缀并可截断为空")
  void testBlobTruncate(Connection connection) throws SQLException {
    Blob blob = connection.createBlob();
    try {
      blob.setBytes(1, new byte[] {1, 2, 3, 4});
      blob.truncate(2);
      assertEquals(2, blob.length());
      assertArrayEquals(new byte[] {1, 2}, blob.getBytes(1, 2));
      blob.truncate(0);
      assertEquals(0, blob.length());
    } finally {
      blob.free();
    }
  }

  @Test
  @DisplayName("Blob 拒绝负长度和非法位置")
  void testBlobInvalidBounds(Connection connection) throws SQLException {
    Blob blob = connection.createBlob();
    try {
      blob.setBytes(1, new byte[] {1, 2});
      // JDBC does not define one portable outcome for truncate beyond the current length.
      assertThrows(SQLException.class, () -> blob.truncate(-1));
      assertThrows(SQLException.class, () -> blob.getBytes(0, 1));
      assertThrows(SQLException.class, () -> blob.setBytes(0, new byte[] {1}));
      assertArrayEquals(new byte[] {1, 2}, blob.getBytes(1, 2));
    } finally {
      blob.free();
    }
  }

  @Test
  @DisplayName("Blob 释放后拒绝读取和修改")
  void testBlobAfterFree(Connection connection) throws SQLException {
    Blob blob = connection.createBlob();
    try {
      blob.setBytes(1, new byte[] {1, 2});
    } finally {
      blob.free();
    }
    assertThrows(SQLException.class, blob::length);
    assertThrows(SQLException.class, () -> blob.getBytes(1, 1));
    assertThrows(SQLException.class, () -> blob.truncate(1));
    assertDoesNotThrow(blob::free, "Repeated free must be harmless");
  }

  @Test
  @DisplayName("Blob 流写入和读取完整保留大数据")
  void testBlobStreamRoundTrip(Connection connection) throws SQLException, IOException {
    byte[] expected = new byte[300 * 1024];
    for (int i = 0; i < expected.length; i++) {
      expected[i] = (byte) (i % 251);
    }
    Blob blob = connection.createBlob();
    try {
      try (OutputStream stream = blob.setBinaryStream(1)) {
        stream.write(expected);
      }
      assertEquals(expected.length, blob.length());
      try (InputStream stream = blob.getBinaryStream()) {
        assertArrayEquals(expected, stream.readAllBytes());
      }
    } finally {
      blob.free();
    }
  }
}
