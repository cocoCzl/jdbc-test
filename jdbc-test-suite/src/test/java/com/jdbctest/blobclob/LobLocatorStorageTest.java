package com.jdbctest.blobclob;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.jdbctest.extension.JdbcContext;
import com.jdbctest.extension.JdbcContracts;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;

class LobLocatorStorageTest {

    @Test
    void materializedLobPersistenceIsAnUnmetSuiteRequirementWithExactReadbackEvidence() {
        var observations = new java.util.HashMap<String, String>();
        org.junit.jupiter.api.TestReporter reporter = observations::putAll;
        var failure = assertThrows(JdbcContracts.SuiteRequirementAssertionError.class,
            () -> LobPersistenceTest.assertPersistentContent(
                "ABCDE".getBytes(StandardCharsets.UTF_8), "ABCDE", reporter));
        assertInstanceOf(AssertionError.class, failure.getCause());
        assertEquals("4142434445", observations.get("jdbc.lob.persistedBinaryHex"));
        assertEquals("ABCDE", observations.get("jdbc.lob.persistedText"));
        LobPersistenceTest.assertPersistentContent(
            "AXYDE".getBytes(StandardCharsets.UTF_8), "AXYDE", reporter);
        assertThrows(JdbcContracts.SuiteRequirementAssertionError.class,
            () -> LobPersistenceTest.assertPersistentContent(null, null, reporter));
    }

    private boolean storage(String binary, String text) throws SQLException {
        var connection = mock(Connection.class);
        var statement = mock(Statement.class);
        var rows = mock(ResultSet.class);
        var metadata = mock(ResultSetMetaData.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(rows);
        when(rows.getMetaData()).thenReturn(metadata);
        when(metadata.getColumnTypeName(1)).thenReturn(binary);
        when(metadata.getColumnTypeName(2)).thenReturn(text);
        try (var jdbc = mockStatic(JdbcContext.class)) {
            jdbc.when(() -> JdbcContext.sql(anyString())).thenAnswer(call -> call.getArgument(0));
            return LobPersistenceTest.usesOidLocators(connection);
        }
    }

    @Test
    void postgresUsesOidLocatorsButKingbaseOracleModeUsesItsNativeFixture() throws Exception {
        assertTrue(storage("oid", "OID"));
        assertFalse(storage("bytea", "text"));
        assertFalse(storage("BLOB", "CLOB"));
    }

    @Test
    void inconsistentFixtureDoesNotSilentlyBindAnOidIntoBinaryStorage() {
        assertThrows(SQLException.class, () -> storage("oid", "text"));
        assertThrows(SQLException.class, () -> storage("bytea", "oid"));
        assertThrows(SQLException.class, () -> storage(null, "text"));
    }
}
