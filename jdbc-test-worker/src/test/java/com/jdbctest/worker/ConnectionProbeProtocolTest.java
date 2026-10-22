package com.jdbctest.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Runs the real Worker protocol with a synthetic driver; never contacts a database. */
class ConnectionProbeProtocolTest {

    @TempDir
    Path directory;

    @Test
    void loadingConnectingAndMetadataFailuresRemainDistinctOnTheWire() throws Exception {
        Path source = directory.resolve("fixture/Driver.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
            package fixture;
            public class Driver implements java.sql.Driver {
                public java.sql.Connection connect(String url, java.util.Properties props)
                    throws java.sql.SQLException {
                    if (url.endsWith("unknown")) {
                        throw new java.sql.SQLException("unknown " + props.getProperty("password"), "ZZ999", 71);
                    }
                    if (url.endsWith("auth")) {
                        throw new java.sql.SQLException("authentication rejected", "28000", 72);
                    }
                    if (url.endsWith("rejected")) return null;
                    return (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(
                        getClass().getClassLoader(), new Class<?>[]{java.sql.Connection.class},
                        (proxy, method, args) -> {
                            if (method.getName().equals("getMetaData")) {
                                throw new java.sql.SQLException("metadata failed", "XX001", 73);
                            }
                            return null;
                        });
                }
                public boolean acceptsURL(String url) { return true; }
                public java.sql.DriverPropertyInfo[] getPropertyInfo(String url, java.util.Properties props) {
                    return new java.sql.DriverPropertyInfo[0];
                }
                public int getMajorVersion() { return 0; }
                public int getMinorVersion() { return 0; }
                public boolean jdbcCompliant() { return false; }
                public java.util.logging.Logger getParentLogger() { return java.util.logging.Logger.getGlobal(); }
            }
            """);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
            "-d", directory.toString(), source.toString()));
        Path jar = directory.resolve("fixture.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("fixture/Driver.class"));
            output.write(Files.readAllBytes(directory.resolve("fixture/Driver.class")));
            output.closeEntry();
        }
        assertFailure(jar, "missing.Driver", "unknown", "CONNECTION_DRIVER_LOAD_FAILED",
            "LOADING_DRIVER", null);
        assertFailure(jar, "fixture.Driver", "unknown", "CONNECTION_UNKNOWN",
            "ESTABLISHING_CONNECTION", "ZZ999");
        assertFailure(jar, "fixture.Driver", "auth", "CONNECTION_AUTHENTICATION_FAILED",
            "ESTABLISHING_CONNECTION", "28000");
        assertFailure(jar, "fixture.Driver", "metadata", "CONNECTION_METADATA_FAILED",
            "READING_METADATA", "XX001");
        assertFailure(jar, "fixture.Driver", "rejected", "DRIVER_DOES_NOT_ACCEPT_URL",
            "ESTABLISHING_CONNECTION", null);
    }

    private void assertFailure(Path jar, String driver, String suffix, String code, String stage,
        String state) throws Exception {
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java")
            .toString(), "-cp", System.getProperty("java.class.path"), WorkerMain.class.getName(),
            "--probe-connection").start();
        try {
            var mapper = new ObjectMapper();
            var request = Map.of("driver", Map.of("driverClass", driver, "artifacts",
                    java.util.List.of(jar.toString())),
                "connection", Map.of("jdbcUrl", "jdbc:fixture:" + suffix, "username", "fixture",
                    "password", "probe-only-secret"));
            process.getOutputStream().write(mapper.writeValueAsBytes(request));
            process.getOutputStream().close();
            assertTrue(process.waitFor(15, TimeUnit.SECONDS));
            String stdout = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(),
                StandardCharsets.UTF_8);
            assertEquals(3, process.exitValue());
            var event = mapper.readTree(stdout);
            assertEquals(code, event.path("errorType").asText());
            assertEquals(stage, event.path("stage").asText());
            if (state != null) {
                assertEquals(state, event.path("diagnostics").get(0).path("sqlState").asText());
                assertTrue(event.path("diagnostics").get(0).path("vendorCode").asInt() > 0);
            }
            assertFalse(stdout.contains("probe-only-secret"));
            assertFalse(stderr.contains("probe-only-secret"));
            assertFalse(stdout.contains("CONNECTION_PROBED"));
        } finally {
            process.destroyForcibly();
            assertTrue(process.waitFor(5, TimeUnit.SECONDS));
        }
    }
}
