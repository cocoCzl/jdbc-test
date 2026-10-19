package com.jdbctest.extension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jdbctest.config.Config;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// Synthetic ownership regression; does not reproduce or explain historical LOB disappearance.
class OracleOwnershipIsolationTest {

    @TempDir
    Path directory;

    @Test
    void reportedLobsAreEvidenceOnlyAndUnconfirmedCreatesAreNeverDropped() throws Exception {
        Config config = new Config();
        config.db = new Config.DbConfig();
        config.db.dialect = "oracle";
        config.namespace = new Config.NamespaceConfig();
        config.namespace.name = "ORACLE_FIXTURE";
        config.namespace.selection = "schema";
        config.namespace.destructiveConsent = true;
        config.namespace.objectPrefix = "j01234567890123456789";
        config.namespace.ledgerPath = directory.resolve("oracle-ownership.yaml").toString();
        TestObjects objects = new TestObjects(config);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getPrimaryKeys(null, "ORACLE_FIXTURE", objects.name("owned")))
            .thenReturn(mock(ResultSet.class));
        when(metadata.getIndexInfo(null, "ORACLE_FIXTURE", objects.name("owned"), false, false))
            .thenReturn(mock(ResultSet.class));
        List<PreparedStatement> queries = new ArrayList<>();
        when(connection.prepareStatement(anyString())).thenAnswer(call -> {
            String sql = call.getArgument(0);
            PreparedStatement query = mock(PreparedStatement.class);
            ResultSet rows = mock(ResultSet.class);
            when(query.executeQuery()).thenReturn(rows);
            if (sql.startsWith("SELECT segment_name FROM all_lobs")) {
                when(rows.next()).thenReturn(true, true, false);
                when(rows.getString(1)).thenReturn("LOB_FIXTURE_A",
                    "LOB_FIXTURE_B");
            }
            queries.add(query);
            return query;
        });
        objects.create(statement, "CREATE TABLE {{owned}} (content CLOB)");
        assertEquals(2, ((List<?>) objects.snapshot().getFirst().get("dependentObjects")).size());
        String collision = "CREATE TABLE {{collision}} (content CLOB)";
        doThrow(new SQLException("ORA-00955: name already used", "42000", 955))
            .when(statement).executeUpdate(objects.sql(collision));
        assertThrows(SQLException.class, () -> objects.create(statement, collision));
        objects.cleanup(connection);
        assertEquals(4, queries.size());
        for (PreparedStatement query : queries) {
            verify(query).setString(1, "ORACLE_FIXTURE");
            verify(query).setString(2, objects.name("owned"));
        }
        var commands = org.mockito.Mockito.mockingDetails(statement).getInvocations().stream()
            .filter(call -> call.getMethod().getName().equals("execute"))
            .map(call -> call.getArgument(0, String.class)).toList();
        assertEquals(List.of("DROP TABLE " + objects.qualified("owned") + " PURGE"), commands);
        assertTrue(commands.stream().noneMatch(sql -> sql.contains("LOB_FIXTURE")));
        assertEquals("UNKNOWN", objects.cleanupEvidence().get("status"));
        assertEquals(1L, objects.cleanupEvidence().get("confirmedDeleted"));
        assertEquals("UNKNOWN", objects.snapshot().get(1).get("state"));
    }
}
