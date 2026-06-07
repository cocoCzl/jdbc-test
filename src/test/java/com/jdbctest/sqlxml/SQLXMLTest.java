package com.jdbctest.sqlxml;

import com.jdbctest.config.Config;
import com.jdbctest.config.ConfigLoader;
import com.jdbctest.extension.JdbcTestExtension;
import com.jdbctest.extension.RequiresFeature;
import com.jdbctest.extension.UseSqlScripts;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.xml.transform.Result;
import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import java.io.*;
import java.sql.*;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(JdbcTestExtension.class)
@RequiresFeature("sqlxml")
@UseSqlScripts(
    ddl = {"sqlxml_ddl.sql"},
    dml = {"sqlxml_dml.sql"}
)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SQLXMLTest {

    private static boolean isOracle() {
        return ConfigLoader.load().db.type == Config.DbType.ORACLE;
    }

    @Test
    @Order(1)
    @DisplayName("getSQLXML 读取 XML 数据")
    void testGetSQLXML(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT xml_data FROM sqlxml_test WHERE id = 1");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            SQLXML xml = rs.getSQLXML("xml_data");
            assertNotNull(xml, "getSQLXML 应返回非空");
            String content = xml.getString();
            assertTrue(content.contains("<root>"), "XML 内容应包含 <root>");
            assertTrue(content.contains("value1"), "XML 内容应包含 value1");
        }
    }

    @Test
    @Order(2)
    @DisplayName("setSQLXML 写入 XML 数据")
    void testSetSQLXML(Connection conn) throws SQLException {
        String xmlContent = "<root><data>测试XML写入</data></root>";
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO sqlxml_test (name, xml_data) VALUES (?, ?)")) {
            ps.setString(1, "setSQLXML测试");
            SQLXML xml = conn.createSQLXML();
            xml.setString(xmlContent);
            ps.setSQLXML(2, xml);
            assertEquals(1, ps.executeUpdate());
        }
    }

    @Test
    @Order(3)
    @DisplayName("SQLXML.getString 获取字符串")
    void testGetString(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT xml_data FROM sqlxml_test WHERE id = 1");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            SQLXML xml = rs.getSQLXML("xml_data");
            String str = xml.getString();
            assertNotNull(str);
            assertFalse(str.isEmpty());
        }
    }

    @Test
    @Order(4)
    @DisplayName("SQLXML.setString 设置字符串")
    void testSetString(Connection conn) throws SQLException {
        SQLXML xml = conn.createSQLXML();
        try {
            xml.setString("<test>hello</test>");
            if (!isOracle()) {
                assertEquals("<test>hello</test>", xml.getString());
            }
            // Oracle XMLType setString sets content in write mode; getString reads after DB round-trip
        } finally {
            xml.free();
        }
    }

    @Test
    @Order(5)
    @DisplayName("SQLXML.getSource 获取 Source")
    void testGetSource(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT xml_data FROM sqlxml_test WHERE id = 1");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            SQLXML xml = rs.getSQLXML("xml_data");
            // Oracle requires a specific Source class, null not supported
            Source source = isOracle() ? xml.getSource(StreamSource.class) : xml.getSource(null);
            assertNotNull(source, "getSource 应返回非空");
        }
    }

    @Test
    @Order(6)
    @DisplayName("SQLXML.setResult 设置 Result")
    void testSetResult(Connection conn) throws SQLException {
        SQLXML xml = conn.createSQLXML();
        try {
            Result result = xml.setResult(StreamResult.class);
            assertNotNull(result, "setResult 应返回非空");
        } finally {
            xml.free();
        }
    }

    @Test
    @Order(7)
    @DisplayName("SQLXML 读写完整流程")
    void testSnapshotScrap(Connection conn) throws SQLException, IOException {
        String inputXml = "<person><name>测试员</name><score>100</score></person>";
        SQLXML xml = conn.createSQLXML();
        try {
            // 写入
            xml.setString(inputXml);

            // 通过流写入数据库
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO sqlxml_test (name, xml_data) VALUES (?, ?)")) {
                ps.setString(1, "完整流程");
                ps.setSQLXML(2, xml);
                ps.executeUpdate();
            }
        } finally {
            xml.free();
        }

        // 读回验证
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT xml_data FROM sqlxml_test WHERE name = '完整流程'");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            SQLXML result = rs.getSQLXML("xml_data");
            String content = result.getString();
            assertTrue(content.contains("测试员"), "读回的 XML 应包含原始内容");
        }
    }
}
