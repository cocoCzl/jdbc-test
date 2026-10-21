package com.jdbctest.extension;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jdbctest.config.Config;
import com.jdbctest.config.ConfigLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDir;

class SqlPreparationTest {

    @UseSqlScripts(ddl = "fixture.sql", dml = "rows.sql")
    static class Fixture {
        void tableCase() {
        }

        void anotherTableCase() {
        }

        @UseSqlScripts
        void independentApi() {
        }
    }

    @TempDir
    Path directory;

    @Test
    void callbacksRecordEachStageAndKeepCleanupFailureSeparate() throws Throwable {
        var context = mock(ExtensionContext.class);
        var store = mock(ExtensionContext.Store.class);
        var values = new HashMap<String, Object>();
        var reports = new HashMap<String, String>();
        when(context.getRoot()).thenReturn(context);
        when(context.getStore(any())).thenReturn(store);
        when(store.get(any(), any())).thenAnswer(call -> values.get(call.getArgument(0)));
        when(store.remove(any(), any())).thenAnswer(call -> values.remove(call.getArgument(0)));
        when(store.remove(any())).thenAnswer(call -> values.remove(call.getArgument(0)));
        org.mockito.Mockito.doAnswer(call -> values.put(call.getArgument(0), call.getArgument(1)))
            .when(store).put(any(), any());
        org.mockito.Mockito.doAnswer(call -> reports.put(call.getArgument(0), call.getArgument(1)))
            .when(context).publishReportEntry(anyString(), anyString());
        org.mockito.Mockito.doReturn(Fixture.class).when(context).getRequiredTestClass();
        when(context.getRequiredTestMethod()).thenReturn(
            Fixture.class.getDeclaredMethod("independentApi"));
        when(context.getExecutionException()).thenReturn(Optional.empty());
        var connection = mock(Connection.class);
        values.put("independentApi_conn", connection);
        var extension = new JdbcTestExtension();
        Config config = new Config();
        config.db = new Config.DbConfig();
        config.db.url = "jdbc:fixture:timing";
        config.adapter = new Config.AdapterConfig();
        config.adapter.capabilities = com.jdbctest.catalog.AdapterExecutionConfig
            .load("postgresql").capabilities();
        config.pool = new Config.PoolConfig();
        config.namespace = new Config.NamespaceConfig();
        when(context.getParent()).thenReturn(Optional.of(context));
        try (var loader = mockStatic(ConfigLoader.class); var jdbc = mockStatic(JdbcContext.class)) {
            loader.when(ConfigLoader::load).thenReturn(config);
            jdbc.when(JdbcContext::getDataSource).thenReturn(mock(javax.sql.DataSource.class));
            jdbc.when(JdbcContext::getConnection).thenReturn(connection);
            extension.beforeEach(context);
            extension.resolveParameter(null, context);
            assertEquals("PREPARATION", reports.get("jdbc.phase"));
        }
        extension.interceptTestMethod(() -> null, null, context);
        extension.afterTestExecution(context);
        org.mockito.Mockito.doThrow(new SQLException("fixture close failure", "08006"))
            .when(connection).close();
        assertThrows(SQLException.class, () -> extension.afterEach(context));
        assertEquals("CLEANUP", reports.get("jdbc.phase"));
        for (String phase : java.util.List.of("PREPARATION", "EXECUTION", "CLEANUP")) {
            assertTrue(Long.parseLong(reports.get("jdbc.phaseDurationMs." + phase)) >= 0);
        }
    }

    @Test
    void unavailableConnectionIsAParameterPreparationFailure() {
        var context = mock(ExtensionContext.class);
        var store = mock(ExtensionContext.Store.class);
        when(context.getRoot()).thenReturn(context);
        when(context.getStore(any())).thenReturn(store);
        try (var jdbc = mockStatic(JdbcContext.class)) {
            jdbc.when(JdbcContext::getConnection)
                .thenThrow(new SQLException("fixture connection unavailable", "08001"));
            var error = assertThrows(org.junit.jupiter.api.extension.ParameterResolutionException.class,
                () -> new JdbcTestExtension().resolveParameter(null, context));
            org.junit.jupiter.api.Assertions.assertInstanceOf(TargetConnectionException.class,
                error.getCause());
            org.junit.jupiter.api.Assertions.assertEquals("08001",
                ((SQLException) error.getCause()).getSQLState());
        }
    }

    @Test
    void absentSqlXmlTableIsPreparationFailureAndDoesNotBlockIndependentApi()
        throws Exception {
        Config config = new Config();
        config.db = new Config.DbConfig();
        config.db.adapterId = "mysql";
        config.db.assetId = "mysql";
        config.db.url = "jdbc:fixture:sqlxml";
        config.pool = new Config.PoolConfig();
        config.adapter = new Config.AdapterConfig();
        config.adapter.capabilities = com.jdbctest.catalog.AdapterExecutionConfig
            .load("mysql").capabilities();
        config.ddl = new Config.DdlConfig();
        config.ddl.basePath = "classpath:ddl";
        config.dml = new Config.DmlConfig();
        config.dml.basePath = "classpath:dml";
        config.namespace = new Config.NamespaceConfig();
        var context = mock(ExtensionContext.class);
        var store = mock(ExtensionContext.Store.class);
        when(context.getStore(any())).thenReturn(store);
        when(context.getParent()).thenReturn(Optional.of(context));
        when(context.getRoot()).thenReturn(context);
        Class<?> test = Class.forName("com.jdbctest.sqlxml.SQLXMLTest");
        org.mockito.Mockito.doReturn(test).when(context).getRequiredTestClass();
        when(context.getRequiredTestMethod()).thenReturn(
            test.getDeclaredMethod("testGetSQLXML", Connection.class));
        var connection = mock(Connection.class);
        var statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.execute(anyString())).thenReturn(false);
        when(statement.execute("SELECT id, name, xml_data FROM {{sqlxml_test}} WHERE 1 = 0"))
            .thenThrow(new SQLException("missing table", "42S02"));
        var objects = mock(TestObjects.class);
        when(objects.sql(anyString())).thenAnswer(call -> call.getArgument(0));
        try (var loader = mockStatic(ConfigLoader.class); var jdbc = mockStatic(JdbcContext.class)) {
            loader.when(ConfigLoader::load).thenReturn(config);
            jdbc.when(JdbcContext::getDataSource).thenReturn(mock(javax.sql.DataSource.class));
            jdbc.when(JdbcContext::getConnection).thenReturn(connection);
            jdbc.when(JdbcContext::objects).thenReturn(objects);
            jdbc.when(() -> JdbcContext.sql(anyString())).thenAnswer(call -> call.getArgument(0));
            var error = assertThrows(ExecutionPrerequisiteException.class,
                () -> new JdbcTestExtension().beforeEach(context));
            assertEquals("FIXTURE_SHAPE_UNAVAILABLE", error.getMessage());
            assertEquals("42S02", ((SQLException) error.getCause()).getSQLState());
            when(context.getRequiredTestMethod()).thenReturn(
                test.getDeclaredMethod("testSetString", Connection.class));
            new JdbcTestExtension().beforeEach(context);
            verify(statement, times(1))
                .execute("SELECT id, name, xml_data FROM {{sqlxml_test}} WHERE 1 = 0");
        }
    }

    @Test
    void classSetupIsLazyAndFailedFixtureDoesNotBlockIndependentApi() throws Exception {
        Files.createDirectories(directory.resolve("postgresql"));
        Files.writeString(directory.resolve("postgresql/fixture.sql"), "SELECT 1;");
        Files.writeString(directory.resolve("postgresql/rows.sql"), "SELECT 2;");
        Config config = new Config();
        config.adapter = new Config.AdapterConfig();
        config.adapter.capabilities = com.jdbctest.catalog.AdapterExecutionConfig
            .load("postgresql").capabilities();
        config.db = new Config.DbConfig();
        config.db.adapterId = "postgresql";
        config.db.assetId = "postgresql";
        config.db.username = "fixture";
        config.db.url = "jdbc:fixture:preparation";
        config.pool = new Config.PoolConfig();
        config.pool.profileDir = directory.toString();
        config.namespace = new Config.NamespaceConfig();
        config.ddl = new Config.DdlConfig();
        config.ddl.basePath = directory.toString();
        config.dml = new Config.DmlConfig();
        config.dml.basePath = directory.toString();
        var context = mock(ExtensionContext.class);
        var store = mock(ExtensionContext.Store.class);
        var values = new HashMap<String, Object>();
        when(store.get(any())).thenAnswer(call -> values.get(call.getArgument(0)));
        org.mockito.Mockito.doAnswer(call -> values.put(call.getArgument(0), call.getArgument(1)))
            .when(store).put(any(), any());
        when(context.getRoot()).thenReturn(context);
        when(context.getParent()).thenReturn(Optional.of(context));
        when(context.getStore(any())).thenReturn(store);
        org.mockito.Mockito.doReturn(Fixture.class).when(context).getRequiredTestClass();
        when(context.getRequiredTestMethod()).thenReturn(Fixture.class.getDeclaredMethod("tableCase"));
        var connection = mock(Connection.class);
        var statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.execute(anyString())).thenThrow(new SQLException("fixture permission", "42501"));
        try (var loader = mockStatic(ConfigLoader.class); var jdbc = mockStatic(JdbcContext.class)) {
            loader.when(ConfigLoader::load).thenReturn(config);
            jdbc.when(JdbcContext::getDataSource).thenReturn(mock(javax.sql.DataSource.class));
            jdbc.when(JdbcContext::getConnection).thenReturn(connection);
            var objects = mock(TestObjects.class);
            when(objects.sql(anyString())).thenAnswer(call -> call.getArgument(0));
            jdbc.when(JdbcContext::objects).thenReturn(objects);
            var extension = new JdbcTestExtension();
            extension.beforeAll(context);
            verify(connection, never()).createStatement();
            assertThrows(ExecutionPrerequisiteException.class, () -> extension.beforeEach(context));
            assertThrows(ExecutionPrerequisiteException.class, () -> extension.beforeEach(context));
            verify(connection, times(1)).createStatement();
            when(context.getRequiredTestMethod()).thenReturn(
                Fixture.class.getDeclaredMethod("independentApi"));
            extension.beforeEach(context);
            verify(connection, times(1)).createStatement();
            values.clear();
            org.mockito.Mockito.doReturn(false).when(statement).execute(anyString());
            when(context.getRequiredTestMethod()).thenReturn(
                Fixture.class.getDeclaredMethod("anotherTableCase"));
            extension.beforeEach(context);
            extension.afterEach(context);
            when(context.getRequiredTestMethod()).thenReturn(Fixture.class.getDeclaredMethod("tableCase"));
            extension.beforeEach(context);
            extension.afterEach(context);
            verify(statement, times(2)).execute("SELECT 1");
            verify(statement, times(1)).execute("SELECT 2");
            jdbc.verify(JdbcContext::cleanupObjects, never());
            extension.afterAll(context);
            jdbc.verify(JdbcContext::cleanupObjects, times(1));
            values.clear();
            jdbc.when(JdbcContext::getConnection)
                .thenThrow(new SQLException("fixture authentication failure", "28000", 8));
            for (int attempt = 0; attempt < 2; attempt++) {
                var failure = assertThrows(
                    org.junit.jupiter.api.extension.ParameterResolutionException.class,
                    () -> extension.beforeEach(context));
                assertTrue(failure.getCause() instanceof TargetConnectionException);
                assertEquals("28000", ((SQLException) failure.getCause()).getSQLState());
            }
        }
    }
}
