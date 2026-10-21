package com.jdbctest.extension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jdbctest.config.Config;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PostgresqlLargeObjectTest {

    @TempDir
    Path directory;

    private TestObjects objects() {
        Config config = new Config();
        config.db = new Config.DbConfig();
        config.db.dialect = "postgresql";
        config.namespace = new Config.NamespaceConfig();
        config.namespace.name = "jdbc_test_fixture";
        config.namespace.selection = "schema";
        config.namespace.destructiveConsent = true;
        config.namespace.objectPrefix = "j01234567890123456789";
        config.namespace.ledgerPath = directory.resolve("ledger.yaml").toString();
        return new TestObjects(config);
    }

    @Test
    void journalsExactOidBeforeCommitAndUnlinksOnlyThatOwnedObject() throws Exception {
        var objects = objects();
        var connection = mock(Connection.class);
        when(connection.getSchema()).thenReturn("jdbc_test_fixture");
        var sql = mock(PreparedStatement.class);
        var rows = mock(ResultSet.class);
        when(connection.prepareStatement("SELECT pg_catalog.lo_from_bytea(0, ?)"))
            .thenReturn(sql);
        when(sql.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true);
        when(rows.getLong(1)).thenReturn(4321L);
        assertEquals(4321L, objects.createPostgresqlLargeObject(connection, "locator", new byte[] {65}));
        assertTrue(Files.readString(directory.resolve("ledger.yaml")).contains("oid: 4321"));
        verify(connection, never()).commit();
        var cleanup = mock(Statement.class);
        when(connection.createStatement()).thenReturn(cleanup);
        objects.cleanup(connection);
        verify(cleanup).execute("SELECT pg_catalog.lo_unlink(4321) WHERE EXISTS "
            + "(SELECT 1 FROM pg_catalog.pg_largeobject_metadata WHERE oid = 4321)");
        assertEquals("SUCCESS", objects.cleanupEvidence().get("status"));
    }

    @Test
    void rejectsWrongNamespaceOrAutoCommitBeforeAllocatingAnything() throws Exception {
        var objects = objects();
        var connection = mock(Connection.class);
        when(connection.getSchema()).thenReturn("public");
        assertThrows(SQLException.class,
            () -> objects.createPostgresqlLargeObject(connection, "locator", new byte[0]));
        when(connection.getSchema()).thenReturn("jdbc_test_fixture");
        when(connection.getAutoCommit()).thenReturn(true);
        assertThrows(SQLException.class,
            () -> objects.createPostgresqlLargeObject(connection, "locator", new byte[0]));
        verify(connection, never()).prepareStatement(anyString());
        assertTrue(objects.snapshot().isEmpty());
    }

    @Test
    void failedAllocationOrUnlinkRemainsUnknownOrFailedNeverSuccessful() throws Exception {
        var objects = objects();
        var connection = mock(Connection.class);
        when(connection.getSchema()).thenReturn("jdbc_test_fixture");
        when(connection.prepareStatement(anyString())).thenThrow(new SQLException("denied", "42501"));
        assertThrows(SQLException.class,
            () -> objects.createPostgresqlLargeObject(connection, "locator", new byte[0]));
        objects.cleanup(connection);
        assertEquals("UNKNOWN", objects.cleanupEvidence().get("status"));
        verify(connection, never()).createStatement();
    }
}
