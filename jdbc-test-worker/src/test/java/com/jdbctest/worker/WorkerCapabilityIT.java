package com.jdbctest.worker;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdbctest.catalog.AdapterExecutionConfig;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;

@Tag("integration")
class WorkerCapabilityIT {

    @TempDir
    Path directory;

    @Test
    void enabledDisabledAndMissingCapabilitiesReachProductionWorkerDistinctly() throws Exception {
        try (var database = new PostgreSQLContainer<>("postgres:16").withPassword(
            "temporary-capability-fixture")) {
            database.start();
            try (var admin = java.sql.DriverManager.getConnection(database.getJdbcUrl(),
                database.getUsername(), database.getPassword());
                var sql = admin.createStatement()) {
                sql.execute("CREATE SCHEMA qa_capability");
            }
            for (String mode : List.of("enabled", "disabled", "missing", "full", "timeout")) {
                Path workspace = Files.createDirectory(directory.resolve(mode));
                var capabilities = new HashMap<>(
                    AdapterExecutionConfig.load("postgresql").capabilities());
                if (mode.equals("disabled")) {
                    capabilities.put("array_type", false);
                }
                if (mode.equals("missing")) {
                    capabilities.remove("array_type");
                }
                Map<String, Object> request = new LinkedHashMap<>();
                request.put("protocolVersion", 2);
                request.put("runId", mode);
                request.put("workspace", workspace.toString());
                request.put("timeoutMs", mode.equals("timeout") ? 1 : 90000);
                request.put("driver",
                    Map.of("driverClass", "org.postgresql.Driver", "artifacts", List.of(
                        Path.of(org.postgresql.Driver.class.getProtectionDomain().getCodeSource()
                            .getLocation()
                            .toURI()).toString())));
                request.put("connection",
                    Map.of("jdbcUrl", database.getJdbcUrl(), "username", database.getUsername(),
                        "password",
                        database.getPassword(), "properties", Map.of(), "adapterId", "postgresql"));
                request.put("selection",
                    Map.of("profile", "FULL", "modules", List.of(), "includeScenarios",
                        mode.equals("full") ? List.of()
                            : List.of("advanced_type.get_array", "rowset.execute_and_navigate"),
                        "excludeScenarios", List.of()));
                request.put("scope",
                    Map.of("database", database.getDatabaseName(), "schema", "qa_capability"));
                request.put("objectPrefix",
                    "j" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
                request.put("destructiveConsent", true);
                request.put("capabilities", capabilities);
                Path out = workspace.resolve("events.jsonl"), err = workspace.resolve(
                    "diagnostics.log");
                var process = new ProcessBuilder("java", "-jar",
                    Path.of("target/jdbc-test-worker-1.2.1-SNAPSHOT-all.jar").toAbsolutePath()
                        .toString()).redirectOutput(out.toFile()).redirectError(err.toFile())
                    .start();
                process.getOutputStream().write(new ObjectMapper().writeValueAsBytes(request));
                process.getOutputStream().close();
                try {
                    assertTrue(process.waitFor(110, TimeUnit.SECONDS));
                } finally {
                    if (process.isAlive()) {
                        process.destroyForcibly();
                    }
                }
                String events = Files.readString(out);
                assertFalse(events.contains(database.getPassword()));
                if (mode.equals("timeout")) {
                    assertNotEquals(0, process.exitValue());
                    assertTrue(events.contains("TIMEOUT"), events);
                    assertTrue(events.contains("UNKNOWN_PENDING_PROCESS_EXIT"));
                    continue;
                }
                if (mode.equals("missing")) {
                    assertNotEquals(0, process.exitValue());
                    assertTrue(events.contains("INVALID_REQUEST"));
                    assertFalse(Files.exists(workspace.resolve("object-ledger.yaml")));
                    continue;
                }
                var report = new ObjectMapper().readTree(
                    Files.readString(workspace.resolve("report/report.json")));
                Path evidenceDirectory = Path.of("target", "acceptance-evidence");
                Files.createDirectories(evidenceDirectory);
                Files.writeString(evidenceDirectory.resolve("postgresql-" + mode + ".json"),
                    report.toPrettyString());
                if (mode.equals("full")) {
                    assertEquals(com.jdbctest.catalog.TestCatalog.loadDefault().testCases().size(),
                        report.path("summary").path("total").asInt());
                    assertEquals(com.jdbctest.catalog.TestCatalog.loadDefault().testCases().size(),
                        report.path("summary").path("completed").asInt(),
                        report.toPrettyString());
                    assertEquals(0, report.path("notRun").size());
                    assertEquals(0, report.path("incomplete").size());
                    for (var object : report.path("objectLedger").path("objects")) {
                        assertEquals("DROPPED", object.path("state").asText(), object.toString());
                    }
                    long functions = java.util.stream.StreamSupport.stream(
                            report.path("objectLedger").path("objects").spliterator(), false)
                        .filter(object -> "FUNCTION".equals(object.path("kind").asText())).count();
                    assertEquals(5, functions, "Comment-prefixed function DDL must be journaled");
                    try (var admin = java.sql.DriverManager.getConnection(database.getJdbcUrl(),
                        database.getUsername(), database.getPassword());
                        var sql = admin.createStatement();
                        var rows = sql.executeQuery(
                            "SELECT count(*) FROM pg_depend WHERE refclassid='pg_namespace'::regclass AND refobjid=(SELECT oid FROM pg_namespace WHERE nspname='qa_capability')")) {
                        assertTrue(rows.next());
                        assertEquals(0, rows.getLong(1),
                            "All fixture objects, including routines, must be removed");
                    }
                    System.out.println("TEMP_PG_FULL_EVIDENCE " + report.path("summary"));
                    continue;
                }
                assertEquals("PASSED",
                    report.path("scenario_results").path("rowset.execute_and_navigate")
                        .path("status")
                        .asText(), report.toPrettyString());
                var array = report.path("scenario_results").path("advanced_type.get_array");
                assertEquals(mode.equals("enabled") ? "PASSED" : "SKIPPED",
                    array.path("status").asText(),
                    report.toPrettyString());
                if (mode.equals("disabled")) {
                    assertTrue(array.path("message").asText().contains("array_type"));
                    assertTrue(array.has("skipSource"));
                }
                assertEquals(2, report.path("summary").path("total").asInt());
                assertEquals(0, report.path("notRun").size());
            }
        }
    }
}
