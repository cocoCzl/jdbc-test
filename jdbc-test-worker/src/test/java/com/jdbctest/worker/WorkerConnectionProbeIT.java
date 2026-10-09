package com.jdbctest.worker;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

@Tag("integration")
class WorkerConnectionProbeIT {

  @Test
  void externalDriverConnectsAndReturnsMetadataWithoutLeakingPassword() throws Exception {
    PostgreSQLContainer<?> database = new PostgreSQLContainer<>("postgres:16").withPassword(
        "probe-secret-6f2d9a");
    try {
      database.start();
    } catch (RuntimeException unavailable) {
      Assumptions.abort("Docker/Testcontainers unavailable: " + unavailable.getMessage());
    }
    try (database) {
      Path worker = Path.of("target", "jdbc-test-worker-1.2.1-SNAPSHOT-all.jar").toAbsolutePath();
      Path driver = Path.of(
          org.postgresql.Driver.class.getProtectionDomain().getCodeSource().getLocation().toURI());
      String password = database.getPassword();
      String request =
          "{\"driver\":{\"driverClass\":\"org.postgresql.Driver\",\"artifacts\":[\"" + driver
              + "\"]},\"connection\":{\"jdbcUrl\":\"" + database.getJdbcUrl() + "\",\"username\":\""
              + database.getUsername() + "\",\"password\":\"" + password + "\"}}";
      Process process = new ProcessBuilder("java", "-jar", worker.toString(),
          "--probe-connection").start();
      process.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
      process.getOutputStream().close();
      assertTrue(process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS));
      String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
      assertEquals(0, process.exitValue(), stderr);
      assertTrue(stdout.contains("CONNECTION_PROBED"));
      assertTrue(stdout.contains("PostgreSQL"));
      assertTrue(Arrays.stream(stdout.split("\\R")).filter(line -> !line.isBlank())
          .allMatch(line -> line.startsWith("{")));
      assertFalse(stdout.contains(password));
      assertFalse(stderr.contains(password));
    }
  }
}
