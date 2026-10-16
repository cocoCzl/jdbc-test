package com.jdbctest.worker;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

@Tag("integration")
class WorkerPostgresIT {

    @Test
    void executableLoadsExternalPostgresDriverAndProducesNdjson() throws Exception {
        PostgreSQLContainer<?> database = new PostgreSQLContainer<>("postgres:16")
            .withPassword("e2e-secret-6f2d9a");
        try {
            database.start();
        } catch (RuntimeException unavailable) {
            Assumptions.abort("Docker/Testcontainers unavailable: " + unavailable.getMessage());
        }
        try (database) {
            Path artifact = Path.of("target", "jdbc-test-worker-1.2.1-SNAPSHOT-all.jar")
                .toAbsolutePath();
            Path workspace = Files.createTempDirectory("jdbc-worker-e2e-");
            String password = database.getPassword();
            try (var admin = java.sql.DriverManager.getConnection(database.getJdbcUrl(),
                database.getUsername(), password);
                var sql = admin.createStatement()) {
                sql.execute("CREATE SCHEMA qa_worker");
            }
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            String safety = mapper.writeValueAsString(java.util.Map.of(
                "scope",
                java.util.Map.of("database", database.getDatabaseName(), "schema", "qa_worker"),
                "objectPrefix", "j01234567890123456789", "destructiveConsent", true,
                "capabilities",
                com.jdbctest.catalog.AdapterExecutionConfig.load("postgresql").capabilities()));
            String request = "{"
                + "\"protocolVersion\":2,\"runId\":\"e2e\", "
                + "\"driver\":{\"driverClass\":\"org.postgresql.Driver\",\"artifacts\":[\""
                + Path.of(
                org.postgresql.Driver.class.getProtectionDomain().getCodeSource().getLocation()
                    .toURI())
                + "\"]},"
                + "\"connection\":{\"jdbcUrl\":\"" + database.getJdbcUrl() + "\",\"username\":\""
                + database.getUsername()
                + "\",\"password\":\"" + password
                + "\",\"properties\":{},\"adapterId\":\"postgresql\"},"
                + "\"selection\":{\"profile\":\"CORE\",\"modules\":[\"connection\",\"statement\"],\"includeScenarios\":[\"connection.get_auto_commit\",\"statement.execute_select\"],\"excludeScenarios\":[]},"
                + "\"workspace\":\"" + workspace + "\",\"timeoutMs\":120000}";
            request = request.substring(0, request.length() - 1) + "," + safety.substring(1);
            Process process = new ProcessBuilder("java", "-jar", artifact.toString()).directory(
                workspace.toFile()).start();
            process.getOutputStream()
                .write(request.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            process.getOutputStream().close();
            assertTrue(process.waitFor(150, java.util.concurrent.TimeUnit.SECONDS),
                "worker did not exit");
            String stdout = new String(process.getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), stderr + "\n" + stdout);
            assertTrue(Arrays.stream(stdout.split("\\R")).filter(line -> !line.isBlank())
                .allMatch(line -> line.trim().startsWith("{")));
            assertTrue(stdout.contains("connection."));
            assertFalse(stdout.contains(password));
            assertFalse(stderr.contains(password), stderr);
            Path report = workspace.resolve("report/report.json");
            assertTrue(Files.isRegularFile(report));
            assertFalse(Files.readString(report).contains(password));
            var persisted = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                Files.readString(report));
            assertEquals(2, persisted.path("summary").path("total").asInt());
            assertEquals(2, persisted.path("summary").path("passed").asInt());
            assertEquals(0, persisted.path("summary").path("failed").asInt());
            assertEquals("PASSED",
                persisted.path("scenario_results").path("connection.get_auto_commit").path("status")
                    .asText());
            assertEquals("PASSED",
                persisted.path("scenario_results").path("statement.execute_select").path("status")
                    .asText());
        }
    }
}
