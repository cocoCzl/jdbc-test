package com.jdbctest.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdbctest.catalog.AdapterExecutionConfig;
import com.jdbctest.catalog.DataSourceProviders;
import com.jdbctest.catalog.ExecutionScope;
import com.jdbctest.catalog.TestCaseDescriptor;
import com.jdbctest.catalog.TestModule;
import com.jdbctest.catalog.TestProfile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;

class WorkerTimeoutIsolationTest {

    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    static class UninterruptibleCall {

        @Test
        @Order(1)
        void first() throws Exception {
            Path root = Path.of(System.getProperty("fixture.path"));
            Files.writeString(root.resolve("entered"), "fixture only");
            while (true) {
                try {
                    Thread.sleep(10_000);
                } catch (InterruptedException ignored) {
                    Files.writeString(root.resolve("interrupted"), "still running");
                }
            }
        }

        @AfterEach
        void cleanup() throws Exception {
            Files.writeString(Path.of(System.getProperty("fixture.path"), "cleanup"), "unsafe overlap");
        }

        @Test
        @Order(2)
        void second() throws Exception {
            Files.writeString(Path.of(System.getProperty("fixture.path"), "second"), "must not run");
        }
    }

    @Test
    void ignoredMethodInterruptEndsTheWorkerAtTaskBudgetWithoutFollowingCalls(
        @TempDir Path directory) throws Exception {
        var adapter = AdapterExecutionConfig.load("postgresql");
        var selected = List.of("first", "second").stream().map(method ->
            new TestCaseDescriptor("fixture." + method,
                new TestModule("fixture", "Fixture", "No database calls"), TestProfile.FULL,
                "Controlled timeout fixture", UninterruptibleCall.class.getName(), method,
                List.of())).toList();
        String driver = Path.of(org.postgresql.Driver.class.getProtectionDomain().getCodeSource()
            .getLocation().toURI()).toString();
        var request = new WorkerRunRequest(2, "timeout-fixture",
            new WorkerRunRequest.DriverRequest("org.postgresql.Driver", List.of(driver)),
            new WorkerRunRequest.ConnectionRequest("jdbc:postgresql://fixture.invalid/unused",
                "", "", Map.of(), "postgresql"),
            new WorkerRunRequest.SelectionRequest("FULL", List.of(),
                List.of("fixture.first", "fixture.second"), List.of()),
            directory.resolve("workspace").toString(), 2000L,
            new ExecutionScope("fixture", "owned_fixture"), "j01234567890123456789",
            DataSourceProviders.empty().capabilities(adapter.capabilities()), true, adapter,
            "frozen-fixture", 50L, selected);
        Path output = directory.resolve("events.ndjson");
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java")
            .toString(), "-Dfixture.path=" + directory, "-cp", System.getProperty("java.class.path"),
            WorkerMain.class.getName()).redirectOutput(output.toFile())
            .redirectError(directory.resolve("stderr.log").toFile()).start();
        try {
            new ObjectMapper().writeValue(child.getOutputStream(), request);
            assertTrue(child.waitFor(15, TimeUnit.SECONDS), "Worker must exit at the task boundary");
            assertEquals(WorkerMain.EXECUTION_FAILURE, child.exitValue());
            String events = Files.readString(output);
            assertTrue(Files.exists(directory.resolve("entered")), events);
            assertTrue(Files.exists(directory.resolve("interrupted")), events);
            assertFalse(Files.exists(directory.resolve("second")));
            assertFalse(Files.exists(directory.resolve("cleanup")));
            var rows = new java.util.ArrayList<com.fasterxml.jackson.databind.JsonNode>();
            for (String line : events.lines().toList()) {
                rows.add(new ObjectMapper().readTree(line));
            }
            assertEquals(List.of("fixture.first"), rows.stream()
                .filter(row -> "CASE_STARTED".equals(row.path("type").asText()))
                .map(row -> row.path("scenarioId").asText()).toList());
            assertTrue(rows.stream().noneMatch(row -> "CASE_FINISHED".equals(
                row.path("type").asText())));
            assertTrue(rows.stream().anyMatch(row -> "RUN_FAILED".equals(row.path("type").asText())
                && "TIMEOUT".equals(row.path("errorType").asText())));
            assertFalse(Files.exists(directory.resolve("workspace/temp/config.json")));
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
                assertTrue(child.waitFor(10, TimeUnit.SECONDS));
            }
        }
    }
}
