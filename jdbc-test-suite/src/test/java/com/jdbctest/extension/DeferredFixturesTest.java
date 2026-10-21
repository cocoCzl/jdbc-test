package com.jdbctest.extension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.io.TempDir;

class DeferredFixturesTest {

    @UseSqlScripts(ddl = "fixture.sql", checks = "SELECT value FROM {{fixture}}", deferred = true)
    static class DeferredCase {
        void scenario(JdbcTestExtension.Fixtures fixtures) {
        }
    }

    @TempDir
    Path directory;

    @Test
    void deferredSetupKeepsTheClassCacheAndPreservesTheOriginalSqlFailure() throws Exception {
        Files.createDirectories(directory.resolve("postgresql"));
        Files.writeString(directory.resolve("postgresql/fixture.sql"), "SELECT 1;");
        Config config = new Config();
        config.db = new Config.DbConfig();
        config.db.adapterId = "postgresql";
        config.db.assetId = "postgresql";
        config.db.url = "jdbc:fixture:deferred";
        config.pool = new Config.PoolConfig();
        config.namespace = new Config.NamespaceConfig();
        config.adapter = new Config.AdapterConfig();
        config.adapter.capabilities = com.jdbctest.catalog.AdapterExecutionConfig
            .load("postgresql").capabilities();
        config.ddl = new Config.DdlConfig();
        config.ddl.basePath = directory.toString();
        config.dml = new Config.DmlConfig();
        config.dml.basePath = directory.toString();
        var context = mock(ExtensionContext.class);
        var store = mock(ExtensionContext.Store.class);
        var values = new HashMap<String, Object>();
        when(store.get(any())).thenAnswer(call -> values.get(call.getArgument(0)));
        doAnswer(call -> values.put(call.getArgument(0), call.getArgument(1)))
            .when(store).put(any(), any());
        when(context.getStore(any())).thenReturn(store);
        when(context.getParent()).thenReturn(Optional.of(context));
        when(context.getRoot()).thenReturn(context);
        doReturn(DeferredCase.class).when(context).getRequiredTestClass();
        var method = DeferredCase.class.getDeclaredMethod("scenario", JdbcTestExtension.Fixtures.class);
        when(context.getRequiredTestMethod()).thenReturn(method);
        var parameter = mock(ParameterContext.class);
        when(parameter.getParameter()).thenReturn(method.getParameters()[0]);
        var connection = mock(Connection.class);
        var statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        var objects = mock(TestObjects.class);
        when(objects.sql(anyString())).thenAnswer(call -> call.getArgument(0));
        try (var loader = mockStatic(ConfigLoader.class); var jdbc = mockStatic(JdbcContext.class)) {
            loader.when(ConfigLoader::load).thenReturn(config);
            jdbc.when(JdbcContext::getDataSource).thenReturn(mock(javax.sql.DataSource.class));
            jdbc.when(JdbcContext::getConnection).thenReturn(connection);
            jdbc.when(JdbcContext::objects).thenReturn(objects);
            jdbc.when(() -> JdbcContext.sql(anyString())).thenAnswer(call -> call.getArgument(0));
            var extension = new JdbcTestExtension();
            extension.beforeEach(context);
            verifyNoInteractions(connection, statement);
            var fixture = (JdbcTestExtension.Fixtures) extension.resolveParameter(parameter, context);
            fixture.prepare();
            fixture.prepare();
            verify(statement, times(1)).execute("SELECT 1");
            verify(statement, times(2)).execute("SELECT value FROM {{fixture}}");
            when(statement.execute("SELECT value FROM {{fixture}}"))
                .thenThrow(new SQLException("not authorized", "42501", 9));
            var failure = assertThrows(ExecutionPrerequisiteException.class, fixture::prepare);
            assertEquals("42501", ((SQLException) failure.getCause()).getSQLState());
            assertEquals(9, ((SQLException) failure.getCause()).getErrorCode());
        }
    }
}
