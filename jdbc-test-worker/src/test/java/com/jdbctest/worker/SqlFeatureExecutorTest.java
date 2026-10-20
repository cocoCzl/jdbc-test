package com.jdbctest.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdbctest.catalog.AdapterExecutionConfig;
import com.jdbctest.catalog.ExecutionScope;
import com.jdbctest.catalog.SqlTestPackage;
import com.jdbctest.extension.JdbcContext;
import com.jdbctest.extension.TestObjects;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Savepoint;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

class SqlFeatureExecutorTest {

    @Test
    void orderedAndMultisetKeepMultiplicityAndExactValues() {
        var multiset = rows("MULTISET", "NUMERIC", List.of("INTEGER", "DECIMAL", "STRING"),
            List.of(Arrays.asList("1", "1.20", null), Arrays.asList("1", "1.20", null), List.of("2", "2.00", "NULL")));
        SqlFeatureExecutor.compareRows(multiset, List.of(List.of("2", "2.0", "NULL"), Arrays.asList("1", "1.2", null), Arrays.asList("1", "1.20", null)));
        assertThrows(AssertionError.class, () -> SqlFeatureExecutor.compareRows(multiset,
            List.of(List.of("2", "2.0", "NULL"), List.of("2", "2.0", "NULL"), Arrays.asList("1", "1.2", null))));
        var ordered = rows("ORDERED", "NUMERIC", List.of("INTEGER"), List.of(List.of("1"), List.of("2")));
        assertThrows(AssertionError.class, () -> SqlFeatureExecutor.compareRows(ordered, List.of(List.of("2"), List.of("1"))));
        assertThrows(AssertionError.class, () -> SqlFeatureExecutor.compareRows(ordered, List.of(List.of("1"))));
        var scale = rows("ORDERED", "SCALE", List.of("DECIMAL"), List.of(List.of("1.20")));
        assertThrows(AssertionError.class, () -> SqlFeatureExecutor.compareRows(scale, List.of(List.of("1.2"))));
        var text = rows("ORDERED", "NUMERIC", List.of("STRING"), List.of(Arrays.asList((Object) null)));
        assertThrows(AssertionError.class, () -> SqlFeatureExecutor.compareRows(text, List.of(List.of("NULL"))));
    }

    @Test
    void unsupportedActualTypesAndWrongColumnCountsAreRejected() throws Exception {
        ResultSet result = mock(ResultSet.class);
        ResultSetMetaData metadata = mock(ResultSetMetaData.class);
        when(result.getMetaData()).thenReturn(metadata);
        when(metadata.getColumnCount()).thenReturn(1);
        when(metadata.getColumnType(1)).thenReturn(Types.DOUBLE);
        assertThrows(AssertionError.class, () -> SqlFeatureExecutor.readRows(result,
            rows("ORDERED", "NUMERIC", List.of("STRING"), List.of())));
        when(metadata.getColumnCount()).thenReturn(2);
        assertThrows(AssertionError.class, () -> SqlFeatureExecutor.readRows(result,
            rows("ORDERED", "NUMERIC", List.of("INTEGER"), List.of())));
    }

    @Test
    void expectedErrorsRequireExactEvidenceAndExcludeInfrastructure() {
        var expected = error("22012");
        assertTrue(SqlFeatureExecutor.expectedError(expected, new SQLException("message not used", "22012")));
        assertFalse(SqlFeatureExecutor.expectedError(expected, new SQLException("division", "42000")));
        assertFalse(SqlFeatureExecutor.expectedError(expected, new SQLTimeoutException("division", "22012")));
        assertFalse(SqlFeatureExecutor.expectedError(error("42501"), new SQLException("permission", "42501")));
        assertFalse(SqlFeatureExecutor.expectedError(error("08006"), new SQLException("connection", "08006")));
        assertFalse(SqlFeatureExecutor.expectedError(expected, new SQLException("permission", "22012", 1031)));
    }

    @Test
    void errorAssertionCannotSwallowBindingOrPreparationAndRecoveryKeepsOriginal() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        Savepoint savepoint = mock(Savepoint.class);
        when(connection.setSavepoint()).thenReturn(savepoint);
        when(connection.prepareStatement("SELECT ? / 0")).thenReturn(statement);
        var step = new SqlTestPackage.Step("expected", "SELECT ? / 0",
            List.of(new SqlTestPackage.Parameter("INTEGER", "1")), error("22012"));
        doThrow(new SQLException("binding failed", "22012")).when(statement).setInt(1, 1);
        try (var context = mockStatic(JdbcContext.class)) {
            context.when(() -> JdbcContext.sql("SELECT ? / 0")).thenReturn("SELECT ? / 0");
            assertThrows(SQLException.class, () -> SqlFeatureExecutor.runStep(connection, step, "c0_",
                System.nanoTime() + 10_000_000_000L, "EXECUTION", new ArrayList<>()));
            verify(statement, never()).execute();
            verify(connection).rollback(savepoint);
        }
        SQLException original = new SQLException("original", "22003");
        SQLException cleanup = new SQLException("recovery failed", "08006");
        when(connection.prepareStatement("SELECT ? / 0")).thenThrow(original);
        doThrow(cleanup).when(connection).rollback(savepoint);
        List<Map<String, Object>> details = new ArrayList<>();
        try (var context = mockStatic(JdbcContext.class)) {
            context.when(() -> JdbcContext.sql("SELECT ? / 0")).thenReturn("SELECT ? / 0");
            assertEquals(original, assertThrows(SQLException.class,
                () -> SqlFeatureExecutor.runStep(connection, step, "c0_", System.nanoTime() + 10_000_000_000L,
                    "EXECUTION", details)));
            assertEquals(cleanup, original.getSuppressed()[0]);
            assertTrue(details.getFirst().containsKey("recoveryFailure"));
        }
    }

    @Test
    void cancelledAndExpiredStepsDoNotExecuteSql() throws Exception {
        Connection connection = mock(Connection.class);
        var step = new SqlTestPackage.Step("query", "SELECT 1", List.of(),
            rows("ORDERED", "NUMERIC", List.of("INTEGER"), List.of(List.of("1"))));
        assertThrows(TimeoutException.class, () -> SqlFeatureExecutor.runStep(connection, step,
            "c0_", 0, "EXECUTION", new ArrayList<>()));
        Thread.currentThread().interrupt();
        try {
            assertThrows(CancellationException.class, () -> SqlFeatureExecutor.runStep(connection, step,
                "c0_", Long.MAX_VALUE, "EXECUTION", new ArrayList<>()));
        } finally {
            Thread.interrupted();
        }
        verify(connection, never()).prepareStatement("SELECT 1");
    }

    @Test
    void preparationFailureIsSeparateFromExpectedExecutionErrorAndCleanupFailure() throws Exception {
        var mapper = new ObjectMapper();
        var source = mapper.readTree(java.nio.file.Files.readString(java.nio.file.Path.of("../examples/sql/postgresql-basics.json")));
        var selected = source.path("cases").get(2).deepCopy();
        var variant = (com.fasterxml.jackson.databind.node.ObjectNode) selected.at("/variants/postgresql");
        var execute = variant.putArray("execute");
        execute.add(mapper.valueToTree(Map.of("id", "expected", "sql", "SELECT 1 / 0", "parameters", List.of(),
            "expect", Map.of("kind", "ERROR", "sqlState", "22012"))));
        ((com.fasterxml.jackson.databind.node.ObjectNode) source).putArray("cases").add(selected);
        var snapshot = new SqlTestPackage(mapper.writeValueAsString(source), null);
        var adapter = AdapterExecutionConfig.load("postgresql");
        var request = new WorkerRunRequest(2, "sql-fixture", new WorkerRunRequest.DriverRequest("fixture.Driver", List.of()),
            new WorkerRunRequest.ConnectionRequest("jdbc:fixture:never", "fixture", "", Map.of(), "postgresql"),
            new WorkerRunRequest.SelectionRequest("CUSTOM", List.of("sql_feature"), List.of("join.aggregate"), List.of()),
            "/tmp/not-created-sql-fixture", 10000L, new ExecutionScope("fixture", "owned"),
            "j" + "a".repeat(20), adapter.capabilities(), true, adapter, null, null, null, null, null, snapshot);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(metadata.getDatabaseMajorVersion()).thenReturn(16);
        when(connection.prepareStatement(org.mockito.ArgumentMatchers.anyString())).thenThrow(new SQLException("preparation failed", "22012"));
        TestObjects objects = mock(TestObjects.class);
        doThrow(new SQLException("cleanup failed", "42501")).when(objects).drop(connection, "c0_items");
        var evidence = new WorkerExecutionEvidence("sql-fixture", SqlFeatureExecutor.selected(request),
            new WorkerEventWriter(mapper, new PrintStream(new ByteArrayOutputStream())));
        try (var context = mockStatic(JdbcContext.class)) {
            context.when(JdbcContext::getConnection).thenReturn(connection);
            context.when(JdbcContext::objects).thenReturn(objects);
            context.when(() -> JdbcContext.sql(org.mockito.ArgumentMatchers.anyString())).thenAnswer(call -> call.getArgument(0));
            new SqlFeatureExecutor(request, evidence).execute();
        }
        var result = evidence.results().getFirst();
        assertEquals("FAILED", result.get("status"));
        assertEquals("PREPARATION_FAILURE", result.get("conclusion"));
        assertEquals("preparation failed", result.get("message"));
        assertTrue(result.get("cleanupSteps").toString().contains("cleanup failed"));
        assertEquals(false, result.get("contractInvoked"));
        verify(connection, never()).prepareStatement("SELECT 1 / 0");
    }

    @Test
    void operationAndEnvironmentEvidenceDoNotBecomeSqlAssertionOrJdbcFailures() {
        assertEquals("PREPARATION_FAILURE", SqlFeatureExecutor.conclusion(
            new SQLException("bind", "22012"), "EXECUTION", "BIND_PARAMETERS", Long.MAX_VALUE));
        assertEquals("CONNECTION_FAILURE", SqlFeatureExecutor.conclusion(
            new SQLException("connect", "08006"), "EXECUTION", "EXECUTE_SQL", Long.MAX_VALUE));
        assertEquals("ENVIRONMENT_FAILURE", SqlFeatureExecutor.conclusion(
            new SQLException("permissions", "42501"), "EXECUTION", "EXECUTE_SQL", Long.MAX_VALUE));
        assertEquals("CLEANUP_FAILURE", SqlFeatureExecutor.conclusion(
            new SQLException("close", "HY000"), "EXECUTION", "CLOSE_STATEMENT", Long.MAX_VALUE));
        assertEquals("SQL_EXECUTION_ERROR", SqlFeatureExecutor.conclusion(
            new SQLException("unexpected syntax", "42601"), "EXECUTION", "EXECUTE_SQL", Long.MAX_VALUE));
        assertEquals("PLATFORM_ERROR", SqlFeatureExecutor.conclusion(
            new IllegalStateException("platform"), "EXECUTION", "EXECUTE_SQL", Long.MAX_VALUE));
    }

    private static SqlTestPackage.Expect error(String state) {
        return new SqlTestPackage.Expect("ERROR", null, List.of(), List.of(), null, null, state, "java.sql.SQLException", null);
    }

    private static SqlTestPackage.Expect rows(String order, String decimal, List<String> columns, List<List<Object>> rows) {
        return new SqlTestPackage.Expect("ROWS", order, columns, rows, decimal, null, null, null, null);
    }
}
