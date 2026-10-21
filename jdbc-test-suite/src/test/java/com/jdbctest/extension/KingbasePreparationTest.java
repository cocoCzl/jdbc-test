package com.jdbctest.extension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.jdbctest.catalog.AdapterExecutionConfig;
import com.jdbctest.config.Config;
import com.jdbctest.config.ConfigLoader;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.io.TempDir;

/** Packaged fixtures through real preparation/ownership; JDBC execution here is a fixture. */
class KingbasePreparationTest {

    @TempDir
    Path directory;

    @Test
    void eachSelectedMethodPreparesIndependentlyAndCleansOwnedObjects() throws Exception {
        for (String type : classes()) {
            Class<?> scenario = Class.forName(type);
            for (Method method : scenario.getDeclaredMethods()) {
                if (method.isAnnotationPresent(Test.class)) {
                    verifyPreparation(scenario, List.of(method));
                }
            }
        }
    }

    @Test
    void relatedMethodsSharePreparationUntilClassCleanup() throws Exception {
        for (String type : classes()) {
            Class<?> scenario = Class.forName(type);
            List<Method> methods = java.util.Arrays.stream(scenario.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Test.class)).toList();
            verifyPreparation(scenario, methods);
        }
    }

    private List<String> classes() {
        return List.of("com.jdbctest.callablestatement.ProcedureBehaviorTest",
            "com.jdbctest.callablestatement.RefCursorTest",
            "com.jdbctest.resultset.RowIdBehaviorTest",
            "com.jdbctest.statement.MultipleResultsTest");
    }

    private void verifyPreparation(Class<?> scenario, List<Method> methods) throws Exception {
        Config config = new Config();
        config.db = new Config.DbConfig();
        config.db.adapterId = "kingbase";
        config.db.assetId = AdapterExecutionConfig.load("kingbase").assetId();
        config.db.dialect = "postgresql";
        config.db.username = "fixture";
        config.db.url = "jdbc:fixture:kingbase-preparation";
        config.adapter = new Config.AdapterConfig();
        config.adapter.capabilities = AdapterExecutionConfig.load("kingbase").capabilities();
        config.pool = new Config.PoolConfig();
        config.ddl = new Config.DdlConfig();
        config.ddl.basePath = "classpath:ddl";
        config.dml = new Config.DmlConfig();
        config.dml.basePath = "classpath:dml";
        config.namespace = new Config.NamespaceConfig();
        config.namespace.name = "kingbase_fixture";
        config.namespace.selection = "schema";
        config.namespace.destructiveConsent = true;
        config.namespace.objectPrefix = "j01234567890123456789";
        config.namespace.ledgerPath = directory.resolve(scenario.getSimpleName() + ".yaml").toString();
        TestObjects objects = new TestObjects(config);
        var context = mock(ExtensionContext.class);
        var store = mock(ExtensionContext.Store.class);
        var values = new HashMap<String, Object>();
        when(context.getRoot()).thenReturn(context);
        when(context.getParent()).thenReturn(Optional.of(context));
        when(context.getStore(any())).thenReturn(store);
        when(store.get(any())).thenAnswer(call -> values.get(call.getArgument(0)));
        doAnswer(call -> values.put(call.getArgument(0), call.getArgument(1)))
            .when(store).put(any(), any());
        org.mockito.Mockito.doReturn(scenario).when(context).getRequiredTestClass();
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        List<String> commands = new ArrayList<>();
        when(statement.executeUpdate(anyString())).thenAnswer(call -> {
            commands.add(call.getArgument(0));
            return 0;
        });
        when(statement.execute(anyString())).thenAnswer(call -> {
            commands.add(call.getArgument(0));
            return false;
        });
        try (var loader = mockStatic(ConfigLoader.class); var jdbc = mockStatic(JdbcContext.class)) {
            loader.when(ConfigLoader::load).thenReturn(config);
            jdbc.when(JdbcContext::getDataSource).thenReturn(mock(javax.sql.DataSource.class));
            jdbc.when(JdbcContext::getConnection).thenReturn(connection);
            jdbc.when(JdbcContext::objects).thenReturn(objects);
            jdbc.when(JdbcContext::cleanupObjects).thenAnswer(call -> {
                objects.cleanup(connection);
                return null;
            });
            var extension = new JdbcTestExtension();
            for (Method method : methods) {
                when(context.getRequiredTestMethod()).thenReturn(method);
                extension.beforeEach(context);
                UseSqlScripts fixture = method.getAnnotation(UseSqlScripts.class);
                if (fixture == null) {
                    fixture = scenario.getAnnotation(UseSqlScripts.class);
                }
                if (fixture != null && fixture.deferred()) {
                    var parameter = mock(ParameterContext.class);
                    var fixtureParameter = java.util.Arrays.stream(method.getParameters())
                        .filter(item -> item.getType() == JdbcTestExtension.Fixtures.class)
                        .findFirst().orElseThrow();
                    when(parameter.getParameter()).thenReturn(fixtureParameter);
                    ((JdbcTestExtension.Fixtures) extension.resolveParameter(parameter, context))
                        .prepare();
                }
            }
            int expected = scenario.getSimpleName().equals("MultipleResultsTest") ? 2 : 1;
            assertEquals(expected, objects.snapshot().size());
            assertEquals(expected, commands.stream().filter(sql -> sql.startsWith("CREATE ")).count());
            assertTrue(objects.snapshot().stream().allMatch(row -> row.get("state").equals("CREATED")));
            assertTrue(commands.stream().noneMatch(sql -> sql.contains("{{")));
            extension.afterAll(context);
            assertEquals("SUCCESS", objects.cleanupEvidence().get("status"));
            assertTrue(objects.snapshot().stream().allMatch(row -> row.get("state").equals("DROPPED")));
        }
    }
}
