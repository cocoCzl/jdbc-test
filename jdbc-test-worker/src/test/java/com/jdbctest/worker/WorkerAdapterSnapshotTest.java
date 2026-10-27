package com.jdbctest.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdbctest.catalog.AdapterExecutionConfig;
import com.jdbctest.catalog.ExecutionScope;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkerAdapterSnapshotTest {

    @TempDir
    Path directory;

    @Test
    void builtinFrozenDialectDrivesScopeAndKeepsProtocolShape() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        for (String dialect : List.of("mysql", "oracle", "postgresql")) {
            var original = AdapterExecutionConfig.load(dialect);
            var snapshot = new AdapterExecutionConfig(dialect, original.assetId(),
                original.dialect(),
                original.identifierQuote(), original.capabilities(),
                original.metadataConstraints());
            ExecutionScope scope =
                "oracle".equals(dialect) ? new ExecutionScope(null, "owned_scope")
                    : "mysql".equals(dialect) ? new ExecutionScope("owned_db", null)
                        : new ExecutionScope("owned_db", "owned_scope");
            WorkerRunRequest request = request(snapshot, scope);
            var validate = WorkerMain.class.getDeclaredMethod("validate", WorkerRunRequest.class);
            validate.setAccessible(true);
            validate.invoke(null, request);
            var write = WorkerMain.class.getDeclaredMethod("writeConfig", Path.class,
                WorkerRunRequest.class);
            write.setAccessible(true);
            Path config = directory.resolve(dialect + ".json");
            write.invoke(new WorkerMain(), config, request);
            var json = mapper.readTree(Files.readString(config));
            assertEquals(dialect, json.at("/db/adapter_id").asText());
            assertEquals(dialect, json.at("/db/dialect").asText());
            assertEquals(original.assetId(), json.at("/db/asset_id").asText());
            assertEquals(snapshot.sqlDialect().selection(),
                json.at("/namespace/selection").asText());
            assertEquals(scope.namespace(snapshot.sqlDialect()),
                json.at("/namespace/name").asText());
            assertEquals(!"oracle".equals(dialect), json.path("namespace").has("database"));
            assertEquals("", json.at("/db/password").asText());
            assertFalse(json.at("/preflight/require_sqlxml").asBoolean());
            var wire = mapper.readTree(mapper.writeValueAsString(snapshot));
            assertEquals(6, wire.size());
            assertFalse(wire.has("sqlDialect"));
            assertEquals(snapshot,
                mapper.readValue(mapper.writeValueAsString(snapshot),
                    AdapterExecutionConfig.class));
        }
    }

    @Test
    void mismatchedSnapshotAndMissingDeclaredScopeFailBeforeExecution() throws Exception {
        var method = WorkerMain.class.getDeclaredMethod("validate", WorkerRunRequest.class);
        method.setAccessible(true);
        var snapshot = AdapterExecutionConfig.load("mysql");
        InvocationTargetException mismatch = assertThrows(InvocationTargetException.class,
            () -> method.invoke(null, request(new AdapterExecutionConfig("private-db", snapshot.assetId(),
                snapshot.dialect(), snapshot.identifierQuote(), snapshot.capabilities(), snapshot.metadataConstraints()),
                new ExecutionScope("owned_db", null))));
        assertEquals("BUILTIN_ADAPTER_REQUIRED", mismatch.getCause().getMessage());
        var custom = new AdapterExecutionConfig("mysql", snapshot.assetId(),
            snapshot.dialect(),
            snapshot.identifierQuote(), snapshot.capabilities(), snapshot.metadataConstraints());
        InvocationTargetException missing = assertThrows(InvocationTargetException.class,
            () -> method.invoke(null, request(custom, new ExecutionScope(null, "owned_scope"))));
        assertEquals(IllegalArgumentException.class, missing.getCause().getClass());
    }

    @Test
    void sourceResourceChangesCannotBeInjectedAsBuiltinSnapshots() throws Exception {
        var original = AdapterExecutionConfig.load("postgresql");
        var changed = new AdapterExecutionConfig(original.id(), "other-resources",
            original.dialect(), original.identifierQuote(), original.capabilities(),
            original.metadataConstraints());
        var validate = WorkerMain.class.getDeclaredMethod("validate", WorkerRunRequest.class);
        validate.setAccessible(true);
        var error = assertThrows(InvocationTargetException.class, () -> validate.invoke(null,
            request(changed, new ExecutionScope("owned_db", "owned_schema"))));
        assertEquals("ADAPTER_SNAPSHOT_MISMATCH", error.getCause().getMessage());
        var compatible = new AdapterExecutionConfig(original.id(), original.assetId(),
            original.dialect(), original.identifierQuote(), original.capabilities(), Map.of());
        validate.invoke(null, request(compatible, new ExecutionScope("owned_db", "owned_schema")));
    }

    @Test
    void retiredPackageFieldsAndValidationCommandAreRejected() throws Exception {
        var mapper = new ObjectMapper();
        var wire = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.valueToTree(
            request(AdapterExecutionConfig.load("postgresql"), new ExecutionScope("owned_db", "owned_schema")));
        for (String field : List.of("sqlPackage", "adapterPackage")) {
            var changed = wire.deepCopy();
            changed.putObject(field).put("fingerprint", "legacy");
            assertThrows(com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException.class,
                () -> mapper.treeToValue(changed, WorkerRunRequest.class));
        }
        assertEquals(WorkerMain.INVALID_REQUEST,
            new WorkerMain().run(new String[] {"--validate-sql-package"}));
    }

    @Test
    void retiredFrozenInputAndDisguisedRequestsFailBeforeDriverLoading() throws Exception {
        var mapper = new ObjectMapper();
        var base = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.valueToTree(
            request(AdapterExecutionConfig.load("postgresql"), new ExecutionScope("owned_db", "owned_schema")));
        for (String field : List.of("id", "assetId", "dialect")) {
            var changed = base.deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode) changed.path("adapterExecution")).put(field, "sqlserver");
            assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,
                () -> mapper.treeToValue(changed, WorkerRunRequest.class));
        }
        var validate = WorkerMain.class.getDeclaredMethod("validate", WorkerRunRequest.class);
        validate.setAccessible(true);
        for (String field : List.of("jdbcUrl", "adapterId", "driverClass")) {
            var changed = base.deepCopy();
            var node = (com.fasterxml.jackson.databind.node.ObjectNode) changed.path(
                field.equals("driverClass") ? "driver" : "connection");
            node.put(field, field.equals("jdbcUrl") ? "jdbc:sqlserver://fixture.invalid"
                : field.equals("driverClass") ? "com.microsoft.sqlserver.jdbc.SQLServerDriver" : "sqlserver");
            var error = assertThrows(InvocationTargetException.class,
                () -> validate.invoke(null, mapper.treeToValue(changed, WorkerRunRequest.class)));
            org.junit.jupiter.api.Assertions.assertTrue(error.getCause().getMessage().contains("DATABASE_SUPPORT_RETIRED"));
        }
    }

    private WorkerRunRequest request(AdapterExecutionConfig snapshot, ExecutionScope scope) {
        return new WorkerRunRequest(2, "fixture",
            new WorkerRunRequest.DriverRequest("fixture.Driver", List.of()),
            new WorkerRunRequest.ConnectionRequest("jdbc:fixture:test", "fixture", "", Map.of(),
                snapshot.id()),
            new WorkerRunRequest.SelectionRequest("FULL", List.of("connection"), List.of(),
                List.of()),
            directory.toString(), 1000L, scope, "j01234567890123456789",
            snapshot.capabilities(), true, snapshot);
    }
}
