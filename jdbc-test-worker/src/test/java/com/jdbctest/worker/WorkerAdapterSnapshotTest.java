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
    void customFrozenDialectDrivesScopeAndKeepsProtocolShape() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        for (String dialect : List.of("mysql", "oracle", "postgresql", "sqlserver")) {
            var original = AdapterExecutionConfig.load(dialect);
            var snapshot = new AdapterExecutionConfig("private-db", original.assetId(),
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
            assertEquals("private-db", json.at("/db/adapter_id").asText());
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
            () -> method.invoke(null, request(snapshot, new ExecutionScope("owned_db", null))));
        assertEquals("ADAPTER_SNAPSHOT_MISMATCH", mismatch.getCause().getMessage());
        var custom = new AdapterExecutionConfig("private-db", snapshot.assetId(),
            snapshot.dialect(),
            snapshot.identifierQuote(), snapshot.capabilities(), snapshot.metadataConstraints());
        InvocationTargetException missing = assertThrows(InvocationTargetException.class,
            () -> method.invoke(null, request(custom, new ExecutionScope(null, "owned_scope"))));
        assertEquals(IllegalArgumentException.class, missing.getCause().getClass());
    }

    @Test
    void importedPackageRoundTripsAndWorkerUsesItsFrozenFiles() throws Exception {
        Path root = Path.of("..").toAbsolutePath().normalize();
        Path zip = directory.resolve("custom.zip");
        Process process = new ProcessBuilder("python3", "-B", "scripts/runner.py", "pack-adapter",
            "--adapter", "examples/adapters/postgresql-local", "--output", zip.toString())
            .directory(root.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(),
            java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
        com.jdbctest.catalog.AdapterPackageSnapshot frozen;
        try (var input = Files.newInputStream(zip)) {
            frozen = com.jdbctest.catalog.AdapterPackageSnapshot.readZip(input);
        }
        var execution = frozen.definition().execution();
        var original = request(execution, new ExecutionScope("owned_db", "owned_schema"));
        var request = new WorkerRunRequest(2, "fixture", original.driver(),
            new WorkerRunRequest.ConnectionRequest("jdbc:fixture:test", "fixture", "", Map.of(),
                execution.id()), original.selection(), original.workspace(), original.timeoutMs(),
            original.scope(), original.objectPrefix(), original.capabilities(), true, execution,
            null, null, null, frozen, Map.of("origin", "IMPORTED", "fingerprint", frozen.fingerprint()));
        ObjectMapper mapper = new ObjectMapper();
        request = mapper.readValue(mapper.writeValueAsString(request), WorkerRunRequest.class);
        assertEquals(frozen, request.adapterPackage());
        var write = WorkerMain.class.getDeclaredMethod("writeConfig", Path.class,
            WorkerRunRequest.class);
        write.setAccessible(true);
        Path config = directory.resolve("imported-config.json");
        write.invoke(new WorkerMain(), config, request);
        var json = mapper.readTree(Files.readString(config));
        assertFalse(json.at("/ddl/base_path").asText().startsWith("classpath:"));
        assertEquals(Files.readString(root.resolve("ddl/postgresql/connection_ddl.sql")),
            Files.readString(Path.of(json.at("/ddl/base_path").asText())
                .resolve("postgresql/connection_ddl.sql")));
        assertEquals("owned_schema", json.at("/namespace/name").asText());
        assertEquals("existing", json.at("/namespace/mode").asText());
    }

    private WorkerRunRequest request(AdapterExecutionConfig snapshot, ExecutionScope scope) {
        return new WorkerRunRequest(2, "fixture",
            new WorkerRunRequest.DriverRequest("fixture.Driver", List.of()),
            new WorkerRunRequest.ConnectionRequest("jdbc:fixture:test", "fixture", "", Map.of(),
                "private-db"),
            new WorkerRunRequest.SelectionRequest("FULL", List.of("connection"), List.of(),
                List.of()),
            directory.toString(), 1000L, scope, "j01234567890123456789",
            snapshot.capabilities(), true, snapshot);
    }
}
