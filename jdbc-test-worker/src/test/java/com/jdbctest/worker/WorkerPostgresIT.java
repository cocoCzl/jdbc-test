package com.jdbctest.worker;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
class WorkerPostgresIT {
    @Test
    void executableLoadsExternalPostgresDriverAndProducesNdjson() throws Exception {
        PostgreSQLContainer<?> database = new PostgreSQLContainer<>("postgres:16-alpine")
                .withPassword("e2e-secret-6f2d9a");
        try {
            database.start();
        } catch (RuntimeException unavailable) {
            Assumptions.abort("Docker/Testcontainers unavailable: " + unavailable.getMessage());
        }
        try (database) {
            Path artifact = Path.of("target", "jdbc-test-worker-1.2.1-SNAPSHOT-all.jar").toAbsolutePath();
            Path workspace = Files.createTempDirectory("jdbc-worker-e2e-");
            String password = database.getPassword();
            String request = "{"
                    + "\"runId\":\"e2e\","
                    + "\"driver\":{\"driverClass\":\"org.postgresql.Driver\",\"artifacts\":[\""
                    + Path.of(org.postgresql.Driver.class.getProtectionDomain().getCodeSource().getLocation().toURI()) + "\"]},"
                    + "\"connection\":{\"jdbcUrl\":\"" + database.getJdbcUrl() + "\",\"username\":\"" + database.getUsername()
                    + "\",\"password\":\"" + password + "\",\"properties\":{},\"adapterId\":\"postgresql\"},"
                    + "\"selection\":{\"profile\":\"CORE\",\"modules\":[\"connection\",\"statement\"],\"includeScenarios\":[\"connection.get_auto_commit\",\"statement.execute_select\"],\"excludeScenarios\":[]},"
                    + "\"workspace\":\"" + workspace + "\",\"timeoutMs\":120000}";
            Process process = new ProcessBuilder("java", "-jar", artifact.toString()).directory(workspace.toFile()).start();
            process.getOutputStream().write(request.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            process.getOutputStream().close();
            assertTrue(process.waitFor(150, java.util.concurrent.TimeUnit.SECONDS), "worker did not exit");
            String stdout = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), stderr + "\n" + stdout);
            assertTrue(Arrays.stream(stdout.split("\\R")).filter(line -> !line.isBlank()).allMatch(line -> line.trim().startsWith("{")));
            assertTrue(stdout.contains("connection."));
            assertFalse(stdout.contains(password));
            assertFalse(stderr.contains(password), stderr);
            Path report = workspace.resolve("report/report.json");
            assertTrue(Files.isRegularFile(report));
            assertFalse(Files.readString(report).contains(password));
            var persisted = new com.fasterxml.jackson.databind.ObjectMapper().readTree(Files.readString(report));
            assertEquals(2, persisted.path("summary").path("total").asInt());
            assertEquals(2, persisted.path("summary").path("passed").asInt());
            assertEquals(0, persisted.path("summary").path("failed").asInt());
            assertEquals("PASSED", persisted.path("scenario_results").path("connection.get_auto_commit").path("status").asText());
            assertEquals("PASSED", persisted.path("scenario_results").path("statement.execute_select").path("status").asText());
        }
    }
}
