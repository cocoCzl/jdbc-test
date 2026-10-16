package com.jdbctest.worker;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdbctest.catalog.AdapterExecutionConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Fault injection only: no real database is contacted.
 */
class WorkerCleanupProcessTest {

    @TempDir
    Path directory;

    @Test
    void connectionFailureAndBlockingCleanupCannotProduceSuccess() throws Exception {
        Path jar = driver();
        for (String mode : List.of("fail", "block")) {
            Path workspace = Files.createDirectory(directory.resolve(mode));
            var request = Map.ofEntries(
                Map.entry("protocolVersion", 2), Map.entry("runId", mode),
                Map.entry("workspace", workspace.toString()), Map.entry("timeoutMs", 2000),
                Map.entry("driver",
                    Map.of("driverClass", "fixture.Driver", "artifacts", List.of(jar.toString()))),
                Map.entry("connection",
                    Map.of("jdbcUrl", "jdbc:fixture:" + mode, "username", "fixture",
                        "password", "", "properties", Map.of(), "adapterId", "postgresql")),
                Map.entry("selection", Map.of("profile", "CORE", "includeScenarios",
                    List.of("wrapper.connection_wrapper"))),
                Map.entry("scope", Map.of("database", "fixture", "schema", "owned_scope")),
                Map.entry("objectPrefix", "j01234567890123456789"),
                Map.entry("capabilities", AdapterExecutionConfig.load("postgresql").capabilities()),
                Map.entry("destructiveConsent", true));
            Path stdout = workspace.resolve("stdout"), stderr = workspace.resolve("stderr");
            Process process = new ProcessBuilder("java", "-cp",
                System.getProperty("java.class.path"),
                WorkerMain.class.getName()).redirectOutput(stdout.toFile())
                .redirectError(stderr.toFile()).start();
            try {
                process.getOutputStream().write(new ObjectMapper().writeValueAsBytes(request));
                process.getOutputStream().close();
                assertTrue(process.waitFor(12, TimeUnit.SECONDS),
                    "Cleanup exceeded Worker deadline");
                assertEquals(WorkerMain.EXECUTION_FAILURE, process.exitValue());
                String events = Files.readString(stdout);
                if (mode.equals("fail")) {
                    var report = new ObjectMapper().readTree(
                        Files.readString(workspace.resolve("report/report.json")));
                    assertEquals(1, report.path("summary").path("passed").asInt(),
                        report.toString());
                    assertEquals(0, report.path("summary").path("failed").asInt());
                    assertEquals("FAILED", report.path("cleanupEvidence").path("status").asText());
                    assertEquals("CLEANUP_FAILURE", report.path("terminationReason").asText());
                    assertTrue(events.contains("CLEANUP_FINISHED"));
                } else {
                    assertTrue(events.contains("TIMEOUT"));
                    assertTrue(events.contains("UNKNOWN_PENDING_PROCESS_EXIT"));
                    assertFalse(events.contains("RUN_FINISHED"));
                }
            } finally {
                if (process.isAlive()) {
                    process.destroyForcibly();
                    assertTrue(process.waitFor(5, TimeUnit.SECONDS));
                }
            }
        }
    }

    private Path driver() throws Exception {
        Path source = directory.resolve("fixture/Driver.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
            package fixture;
            import java.sql.*;
            import java.lang.reflect.Proxy;
            public class Driver implements java.sql.Driver {
              private int connections;
              public Connection connect(String url, java.util.Properties properties) throws SQLException {
                if (!properties.containsKey("password") || !properties.getProperty("password").equals("")) {
                  throw new SQLException("fixture expected literal empty password");
                }
                if (++connections >= 3) {
                  if (url.endsWith("block")) {
                    for (;;) { try { Thread.sleep(100); } catch (InterruptedException ignored) {} }
                  }
                  throw new SQLException("fixture cleanup connection unavailable", "08001", 17);
                }
                return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                  new Class[]{Connection.class}, (p,m,a) -> switch(m.getName()) {
                    case "getAutoCommit", "isWrapperFor" -> true;
                    case "unwrap" -> p;
                    case "isClosed" -> false;
                    case "getCatalog" -> "fixture";
                    case "getSchema" -> "owned_scope";
                    case "getMetaData" -> Proxy.newProxyInstance(getClass().getClassLoader(),
                      new Class[]{DatabaseMetaData.class}, (q,n,b) -> switch(n.getName()) {
                        case "getDatabaseProductName" -> "PostgreSQL";
                        case "getDatabaseProductVersion" -> "16.0";
                        case "getDriverName" -> "PostgreSQL JDBC Driver";
                        case "getDriverVersion" -> "42.7.2";
                        default -> null;
                      });
                    default -> null;
                  });
              }
              public boolean acceptsURL(String u) { return true; }
              public DriverPropertyInfo[] getPropertyInfo(String u, java.util.Properties p) { return new DriverPropertyInfo[0]; }
              public int getMajorVersion() { return 42; }
              public int getMinorVersion() { return 7; }
              public boolean jdbcCompliant() { return false; }
              public java.util.logging.Logger getParentLogger() { return java.util.logging.Logger.getGlobal(); }
            }
            """);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d",
            directory.toString(), source.toString()));
        Path jar = directory.resolve("fixture.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("fixture/Driver.class"));
            output.write(Files.readAllBytes(directory.resolve("fixture/Driver.class")));
            output.closeEntry();
        }
        return jar;
    }
}
