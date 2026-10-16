package com.jdbctest.worker;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdbctest.catalog.AdapterExecutionConfig;
import java.nio.file.*;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.jar.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Real PostgreSQL target; DROP failure is explicit JDBC fault injection around the real driver.
 */
@Tag("integration")
class WorkerQualityPostgresIT {

    @TempDir
    Path directory;

    @Test
    void realFixturesCleanupAndIdentityEvidenceAgreeWithExitCodes() throws Exception {
        try (var database = new PostgreSQLContainer<>("postgres:16")
            .withLabel("jdbc-quality-session", "20261015-worker")) {
            database.start();
            String password = "isolated-worker-only";
            try (var connection = DriverManager.getConnection(database.getJdbcUrl(),
                database.getUsername(), database.getPassword());
                var sql = connection.createStatement()) {
                sql.execute("CREATE ROLE quality_worker LOGIN PASSWORD 'isolated-worker-only'");
                for (String mode : List.of("normal", "drop_fault", "mismatch")) {
                    sql.execute("CREATE SCHEMA qa_" + mode + " AUTHORIZATION quality_worker");
                }
            }
            Path postgres = Path.of(
                org.postgresql.Driver.class.getProtectionDomain().getCodeSource().getLocation()
                    .toURI());
            Path fault = faultDriver(postgres);
            ObjectMapper mapper = new ObjectMapper();
            for (String mode : List.of("normal", "drop_fault", "mismatch")) {
                Path workspace = Files.createDirectory(directory.resolve(mode));
                Map<String, Object> request = new LinkedHashMap<>();
                request.put("protocolVersion", 2);
                request.put("runId", mode);
                request.put("workspace", workspace.toString());
                request.put("timeoutMs", 60000);
                request.put("driver", Map.of("driverClass",
                    mode.equals("drop_fault") ? "fixture.DropFaultDriver" : "org.postgresql.Driver",
                    "artifacts", List.of(postgres.toString(), fault.toString())));
                request.put("connection",
                    Map.of("jdbcUrl", database.getJdbcUrl(), "username", "quality_worker",
                        "password", password, "properties", Map.of(), "adapterId", "postgresql"));
                request.put("selection", Map.of("profile", "CORE", "includeScenarios",
                    List.of("connection.get_auto_commit", "statement.execute_select")));
                request.put("scope",
                    Map.of("database", database.getDatabaseName(), "schema", "qa_" + mode));
                request.put("objectPrefix",
                    "j" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
                request.put("destructiveConsent", true);
                request.put("capabilities",
                    AdapterExecutionConfig.load("postgresql").capabilities());
                var adapter = mapper.valueToTree(AdapterExecutionConfig.load("postgresql"));
                if (mode.equals("mismatch")) {
                    ((com.fasterxml.jackson.databind.node.ObjectNode) adapter.path(
                        "metadataConstraints"))
                        .put("expected_database_product_regex", "Oracle");
                }
                request.put("adapterExecution", adapter);
                Path out = workspace.resolve("events.jsonl"), err = workspace.resolve("stderr.log");
                var process = new ProcessBuilder("java", "-jar",
                    Path.of("target/jdbc-test-worker-1.2.1-SNAPSHOT-all.jar")
                        .toAbsolutePath().toString()).redirectOutput(out.toFile())
                    .redirectError(err.toFile()).start();
                try {
                    process.getOutputStream().write(mapper.writeValueAsBytes(request));
                    process.getOutputStream().close();
                    assertTrue(process.waitFor(80, TimeUnit.SECONDS));
                } finally {
                    if (process.isAlive()) {
                        process.destroyForcibly();
                        assertTrue(process.waitFor(5, TimeUnit.SECONDS));
                    }
                }
                String events = Files.readString(out);
                assertFalse(events.contains(password));
                assertFalse(Files.readString(err).contains(password));
                var report = mapper.readTree(
                    Files.readString(workspace.resolve("report/report.json")));
                assertEquals("PostgreSQL",
                    report.path("metadataEvidence").path("databaseProductName").asText());
                int objects;
                try (var connection = DriverManager.getConnection(database.getJdbcUrl(),
                    "quality_worker", password);
                    var sql = connection.prepareStatement(
                        "SELECT count(*) FROM information_schema.tables WHERE table_schema = ?")) {
                    sql.setString(1, "qa_" + mode);
                    try (var rows = sql.executeQuery()) {
                        assertTrue(rows.next());
                        objects = rows.getInt(1);
                    }
                }
                if (mode.equals("mismatch")) {
                    assertEquals(4, process.exitValue());
                    assertEquals("PREFLIGHT_FAILURE", report.path("terminationReason").asText());
                    assertEquals(0, report.path("summary").path("started").asInt());
                    assertEquals(0, objects);
                    assertEquals(0, report.path("objectLedger").path("objects").size());
                } else {
                    assertEquals(2, report.path("summary").path("passed").asInt(),
                        report.toString());
                    assertEquals(0, report.path("summary").path("failed").asInt());
                    if (mode.equals("normal")) {
                        assertEquals(0, process.exitValue());
                        assertEquals("SUCCESS",
                            report.path("cleanupEvidence").path("status").asText());
                        assertTrue(
                            report.path("cleanupEvidence").path("confirmedDeleted").asInt() > 0);
                        assertEquals(0, objects);
                    } else {
                        assertEquals(4, process.exitValue());
                        assertEquals("FAILED",
                            report.path("cleanupEvidence").path("status").asText());
                        assertEquals("CLEANUP_FAILURE", report.path("terminationReason").asText());
                        assertTrue(
                            report.path("cleanupEvidence").path("retainedObjects").size() > 0);
                        assertTrue(objects > 0);
                    }
                }
                Path evidence = Path.of("target/quality-evidence");
                Files.createDirectories(evidence);
                Files.writeString(evidence.resolve(mode + "-report.json"),
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));
                Files.writeString(evidence.resolve(mode + "-events.jsonl"), events);
                Files.writeString(evidence.resolve(mode + "-verification.json"),
                    mapper.writeValueAsString(Map.of(
                        "exitCode", process.exitValue(), "remainingTables", objects, "target",
                        "isolated postgres:16",
                        "driverArtifact", postgres.getFileName().toString(), "faultInjection",
                        mode.equals("drop_fault"))));
            }
        }
    }

    private Path faultDriver(Path postgres) throws Exception {
        Path source = directory.resolve("fixture/DropFaultDriver.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
            package fixture;
            import java.sql.*;
            import java.lang.reflect.*;
            public class DropFaultDriver extends org.postgresql.Driver {
              private static Object call(Object target, Method method, Object[] args) throws Throwable {
                try { return method.invoke(target, args); }
                catch (InvocationTargetException error) { throw error.getCause(); }
              }
              public Connection connect(String url, java.util.Properties properties) throws SQLException {
                Connection connection = super.connect(url, properties);
                return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{Connection.class},
                  (p,m,a) -> {
                    if (m.getName().equals("createStatement")) {
                      Statement statement = (Statement) call(connection,m,a);
                      return Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{Statement.class},
                        (q,n,b) -> {
                          if (b != null && b.length > 0 && b[0] instanceof String sql && sql.startsWith("DROP "))
                            throw new SQLException("controlled DROP fault injection", "42501", 17);
                          return call(statement,n,b);
                        });
                    }
                    return call(connection,m,a);
                  });
              }
            }
            """);
        assertEquals(0,
            ToolProvider.getSystemJavaCompiler().run(null, null, null, "-cp", postgres.toString(),
                "-d", directory.toString(), source.toString()));
        Path jar = directory.resolve("drop-fault.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("fixture/DropFaultDriver.class"));
            output.write(Files.readAllBytes(directory.resolve("fixture/DropFaultDriver.class")));
            output.closeEntry();
        }
        return jar;
    }
}
