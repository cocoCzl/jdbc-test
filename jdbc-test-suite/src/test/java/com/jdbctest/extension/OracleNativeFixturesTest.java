package com.jdbctest.extension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jdbctest.config.Config;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OracleNativeFixturesTest {

    @TempDir
    Path directory;

    private String resource(String path) throws Exception {
        try (var input = getClass().getClassLoader().getResourceAsStream(path)) {
            assertNotNull(input, path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void nativeCollectionsAndImplicitResultsUseOwnedNamesAndReverseDependencyCleanup()
        throws Exception {
        Config config = new Config();
        config.db = new Config.DbConfig();
        config.db.dialect = "oracle";
        config.namespace = new Config.NamespaceConfig();
        config.namespace.name = "JDBC_TEST_FIXTURE";
        config.namespace.selection = "schema";
        config.namespace.destructiveConsent = true;
        config.namespace.objectPrefix = "j01234567890123456789";
        config.namespace.ledgerPath = directory.resolve("ledger.yaml").toString();
        TestObjects objects = new TestObjects(config);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        ResultSet empty = mock(ResultSet.class);
        PreparedStatement dictionary = mock(PreparedStatement.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getPrimaryKeys(any(), any(), any())).thenReturn(empty);
        when(metadata.getIndexInfo(any(), any(), any(), anyBoolean(), anyBoolean())).thenReturn(empty);
        when(connection.prepareStatement(anyString())).thenReturn(dictionary);
        when(dictionary.executeQuery()).thenReturn(empty);
        List<String> executed = new ArrayList<>();
        when(statement.executeUpdate(anyString())).thenAnswer(call -> {
            executed.add(call.getArgument(0));
            return 0;
        });
        when(statement.execute(anyString())).thenAnswer(call -> {
            String sql = call.getArgument(0);
            if (sql.startsWith("DROP ")) {
                executed.add(sql);
            }
            return false;
        });
        for (String path : List.of("ddl/oracle/advancedtype_ddl.sql",
            "dml/oracle/advancedtype_dml.sql", "ddl/oracle/multiple_results_ddl.sql")) {
            for (String sql : JdbcTestExtension.splitBySlash(resource(path))) {
                JdbcTestExtension.executeManagedStatement(statement, objects, sql);
            }
        }
        assertEquals(5, objects.snapshot().size());
        assertEquals(List.of("TYPE", "TYPE", "TABLE", "TABLE", "PROCEDURE"),
            objects.snapshot().stream().map(item -> item.get("kind")).toList());
        assertTrue(executed.get(0).contains("AS TABLE OF NUMBER(10)"));
        assertTrue(executed.get(2).contains("NESTED TABLE int_array STORE AS"));
        assertFalse(executed.stream().anyMatch(sql -> sql.contains("{{")));
        assertTrue(executed.get(4).contains("DBMS_SQL.RETURN_RESULT(first_result)"));
        assertTrue(executed.get(4).stripTrailing().endsWith("END;"));
        objects.cleanup(connection);
        var drops = executed.subList(5, executed.size());
        assertEquals(5, drops.size());
        assertTrue(drops.get(0).startsWith("DROP PROCEDURE"));
        assertTrue(drops.get(1).startsWith("DROP TABLE"));
        assertTrue(drops.get(2).startsWith("DROP TABLE"));
        assertTrue(drops.get(3).startsWith("DROP TYPE"));
        assertTrue(drops.get(4).startsWith("DROP TYPE"));
        assertEquals("SUCCESS", objects.cleanupEvidence().get("status"));
    }
}
