package com.jdbctest.blobclob;

import com.jdbctest.extension.JdbcTestExtension;
import com.jdbctest.extension.UseSqlScripts;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(JdbcTestExtension.class)
@UseSqlScripts(
    ddl = {"blobclob_ddl.sql"},
    dml = {"blobclob_dml.sql"}
)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BlobClobTest {

    @Test
    @Order(1)
    @DisplayName("getBytes 读取 BYTEA 二进制数据")
    void testGetBytes(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT binary_data FROM blobclob_test WHERE id = 1");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            byte[] bytes = rs.getBytes("binary_data");
            assertNotNull(bytes, "getBytes 应返回非空");
            assertEquals(5, bytes.length, "\"Hello\" 编码后应为 5 字节");
            assertEquals("Hello", new String(bytes, StandardCharsets.UTF_8));
        }
    }

    @Test
    @Order(2)
    @DisplayName("setBytes 写入 BYTEA 二进制数据")
    void testSetBytes(Connection conn) throws SQLException {
        byte[] testData = "Hello World 二进制测试".getBytes(StandardCharsets.UTF_8);
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO blobclob_test (name, binary_data) VALUES (?, ?)")) {
            ps.setString(1, "setBytes测试");
            ps.setBytes(2, testData);
            assertEquals(1, ps.executeUpdate());
        }

        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT binary_data FROM blobclob_test WHERE name = 'setBytes测试'");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            assertArrayEquals(testData, rs.getBytes("binary_data"));
        }
    }

    @Test
    @Order(3)
    @DisplayName("getString 读取 TEXT 文本数据")
    void testGetString(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT text_data FROM blobclob_test WHERE id = 1");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            String text = rs.getString("text_data");
            assertNotNull(text);
            assertEquals("这是一段文本数据", text);
        }
    }

    @Test
    @Order(4)
    @DisplayName("setString 写入 TEXT 文本数据")
    void testSetString(Connection conn) throws SQLException {
        String testText = "这是一段测试文本内容";
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO blobclob_test (name, text_data) VALUES (?, ?)")) {
            ps.setString(1, "setString测试");
            ps.setString(2, testText);
            assertEquals(1, ps.executeUpdate());
        }
    }

    @Test
    @Order(5)
    @DisplayName("setBinaryStream / getBinaryStream 流操作")
    void testBinaryStream(Connection conn) throws SQLException, IOException {
        byte[] data = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO blobclob_test (name, binary_data) VALUES (?, ?)")) {
            ps.setString(1, "流测试");
            ps.setBinaryStream(2, new ByteArrayInputStream(data), data.length);
            assertEquals(1, ps.executeUpdate());
        }

        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT binary_data FROM blobclob_test WHERE name = '流测试'");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            try (InputStream is = rs.getBinaryStream("binary_data")) {
                assertNotNull(is);
                byte[] readData = is.readAllBytes();
                assertArrayEquals(data, readData, "读回的数据应与写入一致");
            }
        }
    }

    @Test
    @Order(6)
    @DisplayName("setCharacterStream / getCharacterStream 字符流操作")
    void testCharacterStream(Connection conn) throws SQLException, IOException {
        String text = "字符流测试内容\n第二行";
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO blobclob_test (name, text_data) VALUES (?, ?)")) {
            ps.setString(1, "字符流");
            ps.setCharacterStream(2, new StringReader(text), text.length());
            assertEquals(1, ps.executeUpdate());
        }

        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT text_data FROM blobclob_test WHERE name = '字符流'");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            try (Reader reader = rs.getCharacterStream("text_data")) {
                assertNotNull(reader);
                BufferedReader br = new BufferedReader(reader);
                assertNotNull(br.readLine());
            }
        }
    }

    @Test
    @Order(7)
    @DisplayName("BYTEA 数据长度检查")
    void testByteaLength(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT binary_data FROM blobclob_test WHERE id = 1");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            byte[] bytes = rs.getBytes("binary_data");
            assertEquals(5, bytes.length, "\"Hello\" 应为 5 字节");
        }
    }

    @Test
    @Order(8)
    @DisplayName("TEXT 数据长度检查")
    void testTextLength(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT text_data FROM blobclob_test WHERE id = 1");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            String text = rs.getString("text_data");
            assertEquals(8, text.length(), "应返回 8 个字符");
        }
    }

    @Test
    @Order(9)
    @DisplayName("大文本数据的读写")
    void testLargeText(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT text_data FROM blobclob_test WHERE id = 2");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            String text = rs.getString("text_data");
            assertNotNull(text);
            assertTrue(text.length() > 10, "大文本数据应长于 10 字符");
        }
    }

    @Test
    @Order(10)
    @DisplayName("NULL 二进制数据的处理")
    void testNullBinary(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT binary_data FROM blobclob_test WHERE id = 2");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            byte[] bytes = rs.getBytes("binary_data");
            assertNull(bytes, "NULL BYTEA 列应返回 null");
            assertTrue(rs.wasNull(), "wasNull 应为 true");
        }
    }

    @Test
    @Order(11)
    @DisplayName("setNull 写入 NULL 二进制")
    void testSetNullBinary(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO blobclob_test (name, binary_data, text_data) VALUES (?, ?, ?)")) {
            ps.setString(1, "null测试");
            ps.setNull(2, Types.BINARY);
            ps.setString(3, "文本不为空");
            ps.executeUpdate();
        }
    }
}
