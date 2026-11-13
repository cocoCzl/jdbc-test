package com.jdbctest.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdbctest.catalog.AdapterExecutionConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/** Selected Statement scenarios must not require another scenario to populate their input. */
@Tag("integration")
class StatementSelectionIT {

    @TempDir
    Path workspace;

    @Test
    void postgresUpdateAndDeleteWorkAloneAndTogether() throws Exception {
        verifySelections("postgresql");
    }

    @Test
    void mysqlUpdateAndDeleteWorkAloneAndTogether() throws Exception {
        verifySelections("mysql");
    }

    private void verifySelections(String adapter) throws Exception {
        boolean postgres = "postgresql".equals(adapter);
        String password = "disposable-selection-fixture";
        String user = postgres ? "postgres" : "root";
        int port = postgres ? 5432 : 3306;
        try (var database = new GenericContainer<>(postgres ? "postgres:16" : "mysql:8.4")
            .withEnv(postgres ? "POSTGRES_PASSWORD" : "MYSQL_ROOT_PASSWORD", password)
            .withEnv(postgres ? "POSTGRES_DB" : "MYSQL_DATABASE", "qa_selection")
            .withExposedPorts(port)
            .waitingFor(Wait.forListeningPort())
            .withStartupTimeout(Duration.ofMinutes(2))) {
            database.start();
            String url = "jdbc:" + adapter + "://" + database.getHost() + ":"
                + database.getMappedPort(port) + "/qa_selection"
                + (postgres ? "" : "?allowPublicKeyRetrieval=true&sslMode=DISABLED");
            Map<String, String> scope = postgres
                ? Map.of("database", "qa_selection", "schema", "public")
                : Map.of("database", "qa_selection");
            Class<?> driver = postgres ? org.postgresql.Driver.class
                : com.mysql.cj.jdbc.Driver.class;
            Path jar = Path.of(driver.getProtectionDomain().getCodeSource().getLocation().toURI());
            List<List<String>> selections = List.of(List.of("statement.execute_update"),
                List.of("statement.execute_delete"), List.of("statement.execute_insert",
                    "statement.execute_update", "statement.execute_delete"));
            for (int index = 0; index < selections.size(); index++) {
                Path runDirectory = Files.createDirectory(workspace.resolve("run-" + index));
                Map<String, Object> request = new LinkedHashMap<>();
                request.put("protocolVersion", 2);
                request.put("runId", "selection-" + index);
                request.put("driver", Map.of("driverClass", driver.getName(), "artifacts",
                    List.of(jar.toString())));
                request.put("connection", Map.of("jdbcUrl", url, "username", user,
                    "password", password, "properties", Map.of(), "adapterId", adapter));
                request.put("selection", Map.of("profile", "CUSTOM", "includeScenarios",
                    selections.get(index), "excludeScenarios", List.of(), "modules", List.of()));
                request.put("scope", scope);
                request.put("objectPrefix",
                    "j" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
                request.put("destructiveConsent", true);
                request.put("capabilities", AdapterExecutionConfig.load(adapter).capabilities());
                request.put("workspace", runDirectory.toString());
                request.put("timeoutMs", 60000);
                var process = new ProcessBuilder("java", "-jar",
                    Path.of("target/jdbc-test-worker-1.2.1-SNAPSHOT-all.jar").toAbsolutePath()
                        .toString())
                    .redirectOutput(runDirectory.resolve("events.jsonl").toFile())
                    .redirectError(runDirectory.resolve("diagnostics.log").toFile()).start();
                try {
                    process.getOutputStream().write(new ObjectMapper().writeValueAsBytes(request));
                    process.getOutputStream().close();
                    assertTrue(process.waitFor(80, TimeUnit.SECONDS), "worker did not exit");
                } finally {
                    if (process.isAlive()) {
                        process.destroyForcibly();
                        assertTrue(process.waitFor(10, TimeUnit.SECONDS), "worker survived kill");
                    }
                }
                var report = new ObjectMapper().readTree(
                    Files.readString(runDirectory.resolve("report/report.json")));
                assertEquals(selections.get(index).size(),
                    report.path("summary").path("total").asInt());
                assertEquals(selections.get(index).size(),
                    report.path("summary").path("passed").asInt(), report.toPrettyString());
                assertEquals(0, report.path("summary").path("failed").asInt());
                assertEquals("SUCCESS", report.path("cleanupEvidence").path("status").asText());
                try (var connection = DriverManager.getConnection(url, user, password);
                    var sql = connection.createStatement();
                    var tables = sql.executeQuery("SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = '" + (postgres ? "public" : "qa_selection") + "'")) {
                    assertTrue(tables.next());
                    assertEquals(0, tables.getInt(1), "owned tables remained after selected run");
                }
            }
        }
    }
}
