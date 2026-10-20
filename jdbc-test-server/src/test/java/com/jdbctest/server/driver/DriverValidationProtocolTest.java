package com.jdbctest.server.driver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Real Worker serialization and Server parsing; synthetic drivers must never connect. */
class DriverValidationProtocolTest {

    @TempDir
    Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void realEventPreservesApiVersionAndIndependentDefiningJarVersions() throws Exception {
        Path dependency = jar("dependency", 99, 99, "unrelated-release", false);
        Path driver = jar("driver", 9, 2, "9.0.0-release", true);
        var result = probe(List.of(dependency, driver));
        var response = parse(result, List.of(dependency, driver));
        assertEquals(9, response.majorVersion());
        assertEquals(2, response.minorVersion());
        assertTrue(response.jdbcCompliant());
        assertEquals("9.0.0-release", response.loadedArtifact().implementationVersion());
        assertEquals("vendor-build", response.loadedArtifact().bundleVersion());
        assertEquals(hash(driver), response.loadedArtifact().sha256());
        var httpBody = mapper.readTree(mapper.writeValueAsBytes(response));
        assertEquals(9, httpBody.path("majorVersion").asInt());
        assertEquals(hash(driver), httpBody.path("loadedArtifact").path("sha256").asString());
    }

    @Test
    void actualZeroFalseAndAbsentManifestVersionsRemainRealResults() throws Exception {
        Path driver = jar("zero", 0, 0, null, true);
        var response = parse(probe(List.of(driver)), List.of(driver));
        assertEquals(0, response.majorVersion());
        assertEquals(0, response.minorVersion());
        assertFalse(response.jdbcCompliant());
        assertNull(response.loadedArtifact().implementationVersion());
        assertNull(response.loadedArtifact().bundleVersion());
        assertEquals(DriverPackageStatus.VALIDATED, response.status());
    }

    @Test
    void sameClassInTwoJarsReportsTheOneActuallySelectedByLoaderOrder() throws Exception {
        Path first = jar("first", 1, 2, "first-release", true);
        Path second = jar("second", 7, 8, "second-release", true);
        var one = parse(probe(List.of(first, second)), List.of(first, second));
        var two = parse(probe(List.of(second, first)), List.of(second, first));
        assertEquals(1, one.majorVersion());
        assertEquals(hash(first), one.loadedArtifact().sha256());
        assertEquals(7, two.majorVersion());
        assertEquals(hash(second), two.loadedArtifact().sha256());
    }

    @Test
    void missingFieldsInRealSerializedEventCannotDefaultToZeroOrFalse() throws Exception {
        Path driver = jar("missing", 9, 2, "release", true);
        var real = probe(List.of(driver));
        for (String key : List.of("majorVersion", "minorVersion", "jdbcCompliant", "driverClass")) {
            ObjectNode event = (ObjectNode) mapper.readTree(real.stdout());
            event.remove(key);
            assertProtocolError(event, List.of(driver));
        }
    }

    @Test
    void invalidTypesRangesAndClassIdentityInRealEventAreRejected() throws Exception {
        Path driver = jar("invalid", 9, 2, "release", true);
        var real = probe(List.of(driver));
        for (String key : List.of("majorVersion", "minorVersion", "jdbcCompliant")) {
            for (String replacement : List.of("null", "\"0\"", "{}", "[]")) {
                ObjectNode event = (ObjectNode) mapper.readTree(real.stdout());
                event.set(key, mapper.readTree(replacement));
                assertProtocolError(event, List.of(driver));
            }
        }
        for (String replacement : List.of("-1", "2147483648", "1.5", "true")) {
            ObjectNode event = (ObjectNode) mapper.readTree(real.stdout());
            event.set("majorVersion", mapper.readTree(replacement));
            assertProtocolError(event, List.of(driver));
        }
        ObjectNode mismatch = (ObjectNode) mapper.readTree(real.stdout());
        mismatch.put("driverClass", "wrong.Driver");
        assertProtocolError(mismatch, List.of(driver));
    }

    @Test
    void legacyEventWithoutArtifactIsExplicitlyUnknownButMalformedEvidenceFails() throws Exception {
        Path driver = jar("legacy", 9, 2, "release", true);
        var real = probe(List.of(driver));
        ObjectNode legacy = (ObjectNode) mapper.readTree(real.stdout());
        legacy.remove("loadedArtifact");
        var response = parse(new WorkerProcessRunner.Result(0, false,
            mapper.writeValueAsString(legacy), ""), List.of(driver));
        assertNull(response.loadedArtifact());
        ObjectNode invalid = (ObjectNode) mapper.readTree(real.stdout());
        ((ObjectNode) invalid.path("loadedArtifact")).put("sha256", "invalid");
        assertProtocolError(invalid, List.of(driver));
        ObjectNode version = (ObjectNode) mapper.readTree(real.stdout());
        ((ObjectNode) version.path("loadedArtifact")).put("implementationVersion", 9);
        assertProtocolError(version, List.of(driver));
    }

    private void assertProtocolError(ObjectNode event, List<Path> artifacts) throws Exception {
        var error = assertThrows(DriverException.class, () -> parse(
            new WorkerProcessRunner.Result(0, false, mapper.writeValueAsString(event), ""), artifacts));
        assertEquals("DRIVER_VALIDATION_PROTOCOL_ERROR", error.code());
    }

    private DriverValidationResponse parse(WorkerProcessRunner.Result result, List<Path> artifacts)
        throws Exception {
        WorkerProcessRunner transport = mock(WorkerProcessRunner.class);
        when(transport.run(eq("--validate-driver"), any(), any())).thenReturn(result);
        return new DriverValidationProcess(new DriverWorkerProperties(), transport, mapper)
            .validate("example.Driver", artifacts);
    }

    private WorkerProcessRunner.Result probe(List<Path> artifacts) throws Exception {
        // A launcher manifest references real Worker classes and dependencies from the test reactor.
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS,
            com.jdbctest.worker.WorkerMain.class.getName());
        String classpath = Arrays.stream(System.getProperty("java.class.path")
            .split(java.io.File.pathSeparator)).map(value -> Path.of(value).toUri().toString())
            .collect(java.util.stream.Collectors.joining(" "));
        manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, classpath);
        Path launcher = directory.resolve("worker.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(launcher), manifest)) {
            output.flush();
        }
        var properties = new DriverWorkerProperties();
        properties.setExecutable(launcher);
        var result = new WorkerProcessRunner(properties, mapper).run("--validate-driver",
            Map.of("driverClass", "example.Driver", "artifacts",
                artifacts.stream().map(Path::toString).toList()), Duration.ofSeconds(15));
        assertFalse(result.timedOut());
        assertEquals(0, result.exitCode(), result.stdout());
        assertEquals("DRIVER_VALIDATED", mapper.readTree(result.stdout()).path("type").asString());
        return result;
    }

    private Path jar(String name, int major, int minor, String release, boolean driver)
        throws Exception {
        Path root = Files.createDirectories(directory.resolve(name));
        if (driver) {
            Path source = root.resolve("example/Driver.java");
            Files.createDirectories(source.getParent());
            Files.writeString(source, """
                package example;
                public class Driver implements java.sql.Driver {
                    public int getMajorVersion() { return %d; }
                    public int getMinorVersion() { return %d; }
                    public boolean jdbcCompliant() { return %s; }
                    public java.sql.Connection connect(String u, java.util.Properties p) {
                        throw new AssertionError("Validation must not connect");
                    }
                    public boolean acceptsURL(String u) { return false; }
                    public java.sql.DriverPropertyInfo[] getPropertyInfo(String u, java.util.Properties p) {
                        throw new AssertionError("Not part of metadata validation");
                    }
                    public java.util.logging.Logger getParentLogger() {
                        return java.util.logging.Logger.getGlobal();
                    }
                }
                """.formatted(major, minor, major != 0));
            assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "--release", "21", "-d", root.toString(), source.toString()));
        }
        Manifest manifest = null;
        if (release != null) {
            manifest = new Manifest();
            manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
            manifest.getMainAttributes().put(Attributes.Name.IMPLEMENTATION_VERSION, release);
            manifest.getMainAttributes().putValue("Bundle-Version", "vendor-build");
        }
        Path jar = root.resolve("driver.jar");
        try (var stream = Files.newOutputStream(jar);
            var output = manifest == null ? new JarOutputStream(stream)
                : new JarOutputStream(stream, manifest)) {
            if (driver) {
                output.putNextEntry(new JarEntry("example/Driver.class"));
                output.write(Files.readAllBytes(root.resolve("example/Driver.class")));
                output.closeEntry();
            }
        }
        return jar;
    }

    private String hash(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(Files.readAllBytes(path)));
    }
}
