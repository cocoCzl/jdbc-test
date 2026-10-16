package com.jdbctest.sqlxml;

import static org.junit.jupiter.api.Assertions.*;

import com.jdbctest.extension.JdbcContext;
import com.jdbctest.extension.JdbcTestExtension;
import com.jdbctest.extension.RequiresFeature;
import com.jdbctest.extension.UseSqlScripts;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(JdbcTestExtension.class)
@Tag("integration")
@RequiresFeature("sqlxml")
@UseSqlScripts(ddl = "sqlxml_stream_ddl.sql")
class SqlXmlStreamTest {

    private static final String CONTENT = "<root><value>中文 &amp; streams</value></root>";

    private void assertDocument(InputStream content) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        var document = factory.newDocumentBuilder().parse(content);
        assertEquals("root", document.getDocumentElement().getTagName());
        assertEquals("中文 & streams",
            document.getElementsByTagName("value").item(0).getTextContent());
    }

    private void insert(Connection connection, SQLXML xml) throws SQLException {
        try (PreparedStatement sql = connection.prepareStatement(JdbcContext.sql(
            "INSERT INTO {{sqlxml_stream}} (id, content) VALUES (1, ?)"))) {
            sql.setSQLXML(1, xml);
            assertEquals(1, sql.executeUpdate());
        }
    }

    @Test
    @DisplayName("SQLXML Writer/Reader 完整往返")
    void testCharacterStreams(Connection connection) throws Exception {
        connection.setAutoCommit(false);
        try {
            SQLXML xml = connection.createSQLXML();
            try {
                try (Writer writer = xml.setCharacterStream()) {
                    writer.write(CONTENT);
                }
                insert(connection, xml);
            } finally {
                xml.free();
            }
            try (Statement sql = connection.createStatement();
                ResultSet rows = sql.executeQuery(JdbcContext.sql(
                    "SELECT content FROM {{sqlxml_stream}} WHERE id = 1"))) {
                assertTrue(rows.next());
                SQLXML actual = rows.getSQLXML(1);
                try {
                    try (Reader reader = actual.getCharacterStream();
                        StringWriter text = new StringWriter()) {
                        reader.transferTo(text);
                        assertDocument(new ByteArrayInputStream(
                            text.toString().getBytes(StandardCharsets.UTF_8)));
                    }
                } finally {
                    actual.free();
                }
                assertFalse(rows.next());
            }
        } finally {
            connection.rollback();
        }
    }

    @Test
    @DisplayName("SQLXML 二进制流完整往返")
    void testBinaryStreams(Connection connection) throws Exception {
        connection.setAutoCommit(false);
        try {
            SQLXML xml = connection.createSQLXML();
            try {
                try (OutputStream output = xml.setBinaryStream()) {
                    output.write(CONTENT.getBytes(StandardCharsets.UTF_8));
                }
                insert(connection, xml);
            } finally {
                xml.free();
            }
            try (Statement sql = connection.createStatement();
                ResultSet rows = sql.executeQuery(JdbcContext.sql(
                    "SELECT content FROM {{sqlxml_stream}} WHERE id = 1"))) {
                assertTrue(rows.next());
                SQLXML actual = rows.getSQLXML(1);
                try {
                    try (InputStream input = actual.getBinaryStream()) {
                        assertDocument(input);
                    }
                } finally {
                    actual.free();
                }
                assertFalse(rows.next());
            }
        } finally {
            connection.rollback();
        }
    }
}
