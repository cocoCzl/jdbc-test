package com.jdbctest.blobclob;

import static org.junit.jupiter.api.Assertions.*;

import com.jdbctest.extension.JdbcContext;
import com.jdbctest.extension.JdbcContracts;
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
        Blob blob = JdbcContracts.optional("Connection.createBlob", connection::createBlob);
        try {
            Clob clob = JdbcContracts.optional("Connection.createClob", connection::createClob);
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
    @UseSqlScripts(ddl = "lob_locator_ddl.sql")
    @DisplayName("锁定行的 LOB 定位器修改提交后对新连接可见")
    void testLocatorUpdate(Connection connection, TestReporter reporter)
        throws SQLException, IOException {
        connection.setAutoCommit(false);
        Throwable primary = null;
        try {
            boolean oidLocators = usesOidLocators(connection);
            reporter.publishEntry("jdbc.lob.storage", oidLocators ? "OID" : "NATIVE");
            insertLocator(connection, oidLocators);
            connection.commit();
            try (Statement sql = connection.createStatement();
                ResultSet rows = sql.executeQuery(JdbcContext.sql(
                    "SELECT binary_data, text_data FROM {{lob_locator}} WHERE id = 3 FOR UPDATE"))) {
                assertTrue(rows.next());
                Blob blob = rows.getBlob(1);
                try {
                    Clob clob = rows.getClob(2);
                    try {
                        reporter.publishEntry("jdbc.lob.blobClass", blob.getClass().getName());
                        reporter.publishEntry("jdbc.lob.clobClass", clob.getClass().getName());
                        assertArrayEquals("ABCDE".getBytes(StandardCharsets.UTF_8),
                            blob.getBytes(1, Math.toIntExact(blob.length())));
                        assertEquals("ABCDE", clob.getSubString(1, Math.toIntExact(clob.length())));
                        assertEquals(2, blob.setBytes(2, new byte[] {'X', 'Y'}));
                        try (Writer stream = JdbcContracts.optional("Clob.setCharacterStream",
                            () -> clob.setCharacterStream(2))) {
                            stream.write("XY");
                        }
                        assertArrayEquals("AXYDE".getBytes(StandardCharsets.UTF_8),
                            blob.getBytes(1, Math.toIntExact(blob.length())));
                        assertEquals("AXYDE", clob.getSubString(1, Math.toIntExact(clob.length())));
                        reporter.publishEntry("jdbc.lob.localMutation", "AXYDE");
                    } finally {
                        clob.free();
                    }
                } finally {
                    blob.free();
                }
            }
            connection.commit();
            try (Connection observer = JdbcContext.getConnection()) {
                observer.setAutoCommit(false);
                try {
                    assertLocatorContent(observer, reporter);
                } finally {
                    observer.rollback();
                }
            }
        } catch (SQLException | IOException | RuntimeException | Error error) {
            primary = error;
            throw error;
        } finally {
            try {
                connection.rollback();
                try (Statement sql = connection.createStatement()) {
                    sql.executeUpdate(JdbcContext.sql("DELETE FROM {{lob_locator}} WHERE id = 3"));
                }
                connection.commit();
            } catch (SQLException cleanup) {
                if (primary == null) {
                    throw cleanup;
                }
                primary.addSuppressed(cleanup);
            }
            // OIDs are tracked independently of the row/table and cleaned by TestObjects even
            // when a mutation, observer read, or row cleanup fails. free() never unlinks a LOB.
        }
    }

    static boolean usesOidLocators(Connection connection) throws SQLException {
        try (Statement sql = connection.createStatement();
            ResultSet rows = sql.executeQuery(JdbcContext.sql(
                "SELECT binary_data, text_data FROM {{lob_locator}} WHERE 1 = 0"))) {
            ResultSetMetaData metadata = rows.getMetaData();
            String binaryType = metadata.getColumnTypeName(1);
            String textType = metadata.getColumnTypeName(2);
            if (binaryType == null || binaryType.isBlank() || textType == null || textType.isBlank()) {
                throw new SQLException("Locator fixture column types were not provided");
            }
            boolean binaryOid = "oid".equalsIgnoreCase(binaryType);
            boolean textOid = "oid".equalsIgnoreCase(textType);
            if (binaryOid != textOid) {
                throw new SQLException("Locator fixture mixes OID and native storage");
            }
            return binaryOid;
        }
    }

    private void insertLocator(Connection connection, boolean oidLocators) throws SQLException {
        byte[] bytes = "ABCDE".getBytes(StandardCharsets.UTF_8);
        try (PreparedStatement sql = connection.prepareStatement(JdbcContext.sql(
            "INSERT INTO {{lob_locator}} (id, binary_data, text_data) VALUES (?, ?, ?)"))) {
            sql.setInt(1, 3);
            if (oidLocators) {
                sql.setLong(2, JdbcContext.objects().createPostgresqlLargeObject(
                    connection, "locator_binary", bytes));
                sql.setLong(3, JdbcContext.objects().createPostgresqlLargeObject(
                    connection, "locator_text", bytes));
            } else {
                sql.setBinaryStream(2, new ByteArrayInputStream(bytes), bytes.length);
                sql.setCharacterStream(3, new StringReader("ABCDE"), 5);
            }
            assertEquals(1, sql.executeUpdate());
        }
    }

    static void assertPersistentContent(byte[] binary, String text, TestReporter reporter) {
        reporter.publishEntry("jdbc.lob.persistedBinaryHex",
            binary == null ? "SQL_NULL" : java.util.HexFormat.of().formatHex(binary));
        reporter.publishEntry("jdbc.lob.persistedText", text == null ? "SQL_NULL" : text);
        try {
            assertArrayEquals("AXYDE".getBytes(StandardCharsets.UTF_8), binary);
            assertEquals("AXYDE", text);
        } catch (AssertionError evidence) {
            // JDBC allows materialized LOB objects. Automatic write-through is a suite feature
            // requirement, not established by getBlob/getClob or an adapter declaration.
            throw new JdbcContracts.SuiteRequirementAssertionError(
                "LOB locator mutations must persist without explicit rebinding; "
                    + "JDBC getBlob/getClob alone does not establish a write-through locator",
                evidence);
        }
    }

    private void assertLocatorContent(Connection connection, TestReporter reporter)
        throws SQLException {
        try (Statement sql = connection.createStatement();
            ResultSet rows = sql.executeQuery(JdbcContext.sql(
                "SELECT binary_data, text_data FROM {{lob_locator}} WHERE id = 3"))) {
            assertTrue(rows.next());
            Blob blob = rows.getBlob(1);
            try {
                Clob clob = rows.getClob(2);
                try {
                    assertPersistentContent(blob.getBytes(1, Math.toIntExact(blob.length())),
                        clob.getSubString(1, Math.toIntExact(clob.length())), reporter);
                } finally {
                    clob.free();
                }
            } finally {
                blob.free();
            }
            assertFalse(rows.next());
        }
    }
}
