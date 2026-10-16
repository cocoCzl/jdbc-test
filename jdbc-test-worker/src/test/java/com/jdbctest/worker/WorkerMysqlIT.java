package com.jdbctest.worker;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdbctest.catalog.AdapterExecutionConfig;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

@Tag("integration")
class WorkerMysqlIT {

    @TempDir
    Path workspace;

    @Test
    void connectionAndStatementUseExplicitCatalogAndLeaveNoOwnedObjects() throws Exception {
        try (var database = new GenericContainer<>("mysql:8.4").withEnv("MYSQL_ROOT_PASSWORD",
                "temporary-worker-fixture")
            .withEnv("MYSQL_DATABASE", "qa_worker").withExposedPorts(3306)
            .waitingFor(Wait.forLogMessage(".*ready for connections.*port: 3306.*\\n", 1))
            .withStartupTimeout(Duration.ofMinutes(2))) {
            database.start();
            String url = "jdbc:mysql://" + database.getHost() + ":" + database.getMappedPort(3306)
                + "/?allowPublicKeyRetrieval=true&sslMode=DISABLED";
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("protocolVersion", 2);
            request.put("runId", "mysql-fixture");
            request.put("workspace", workspace.toString());
            request.put("timeoutMs", 90000);
            request.put("driver",
                Map.of("driverClass", "com.mysql.cj.jdbc.Driver", "artifacts", List.of(
                    Path.of(com.mysql.cj.jdbc.Driver.class.getProtectionDomain().getCodeSource()
                        .getLocation()
                        .toURI()).toString())));
            request.put("connection",
                Map.of("jdbcUrl", url, "username", "root", "password", "temporary-worker-fixture",
                    "properties", Map.of(), "adapterId", "mysql"));
            request.put("selection",
                Map.of("profile", "FULL", "modules", List.of("connection", "statement"),
                    "includeScenarios", List.of(), "excludeScenarios", List.of()));
            request.put("scope", Map.of("database", "qa_worker"));
            request.put("objectPrefix", "j01234567890123456789");
            request.put("destructiveConsent", true);
            request.put("capabilities", AdapterExecutionConfig.load("mysql").capabilities());
            var process = new ProcessBuilder("java", "-jar",
                Path.of("target/jdbc-test-worker-1.2.1-SNAPSHOT-all.jar").toAbsolutePath()
                    .toString())
                .redirectOutput(workspace.resolve("events.jsonl").toFile())
                .redirectError(workspace.resolve("diagnostics.log").toFile()).start();
            process.getOutputStream().write(new ObjectMapper().writeValueAsBytes(request));
            process.getOutputStream().close();
            try {
                assertTrue(process.waitFor(110, TimeUnit.SECONDS));
            } finally {
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
            }
            var report = new ObjectMapper().readTree(
                Files.readString(workspace.resolve("report/report.json")));
            Path evidenceDirectory = Path.of("target", "acceptance-evidence");
            Files.createDirectories(evidenceDirectory);
            Files.writeString(evidenceDirectory.resolve("mysql-modules.json"),
                report.toPrettyString());
            assertEquals(40, report.path("summary").path("total").asInt());
            assertEquals(40, report.path("summary").path("completed").asInt(),
                report.toPrettyString());
            assertEquals(0, report.path("notRun").size());
            assertEquals(0, report.path("incomplete").size());
            for (var object : report.path("objectLedger").path("objects")) {
                assertEquals("DROPPED", object.path("state").asText(), object.toString());
            }
            System.out.println("TEMP_MYSQL_MODULE_EVIDENCE " + report.path("summary"));
        }
    }
}
