package com.jdbctest.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Read-only external DM smoke; writable contract runs use the normal authorized task flow. */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "DM_JDBC_URL", matches = ".+")
class WorkerDmIT {

    @Test
    void suppliedPrivateDriverValidatesAndProbesTheActualDmMetadata() throws Exception {
        String artifact = required("DM_JDBC_JAR");
        assertTrue(Files.isRegularFile(Path.of(artifact)));
        String password = required("DM_PASSWORD");
        var driver = Map.of("driverClass", "dm.jdbc.driver.DmDriver", "artifacts",
            List.of(artifact));
        assertEquals("DRIVER_VALIDATED", command("--validate-driver", driver, password)
            .path("type").asText());
        JsonNode metadata = command("--probe-connection", Map.of("driver", driver,
            "connection", Map.of("jdbcUrl", required("DM_JDBC_URL"), "username",
                required("DM_USERNAME"), "password", password)), password);
        assertEquals("CONNECTION_PROBED", metadata.path("type").asText());
        assertEquals("DM DBMS", metadata.path("databaseProductName").asText());
        assertEquals("dm.jdbc.driver.DmDriver", metadata.path("driverName").asText());
        assertEquals(required("DM_SCHEMA"), metadata.path("currentSchema").asText());
        assertFalse(metadata.path("databaseProductVersion").asText().isBlank());
        assertFalse(metadata.path("driverVersion").asText().isBlank());
    }

    private JsonNode command(String mode, Map<String, ?> request, String password) throws Exception {
        var mapper = new ObjectMapper();
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java")
            .toString(), "-jar", Path.of("target", "jdbc-test-worker-1.2.1-SNAPSHOT-all.jar")
            .toAbsolutePath().toString(), mode).start();
        try {
            process.getOutputStream().write(mapper.writeValueAsBytes(request));
            process.getOutputStream().close();
            assertTrue(process.waitFor(45, TimeUnit.SECONDS));
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            assertFalse(stdout.contains(password));
            assertFalse(stderr.contains(password));
            assertEquals(0, process.exitValue(), "DM validation/probe failed; inspect redacted diagnostics");
            return mapper.readTree(stdout);
        } finally {
            process.destroyForcibly();
            assertTrue(process.waitFor(5, TimeUnit.SECONDS));
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("External DM verification requires " + name);
        }
        return value;
    }
}
