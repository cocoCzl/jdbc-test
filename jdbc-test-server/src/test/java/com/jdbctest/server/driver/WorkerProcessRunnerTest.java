package com.jdbctest.server.driver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class WorkerProcessRunnerTest {

    @TempDir
    Path directory;

    @Test
    void largeCatalogRetainsCompleteProtocolJsonAndBoundsDiagnostics() throws Exception {
        var runner = runner();
        var result = runner.run("--describe-catalog", java.util.Map.of(), Duration.ofSeconds(10));
        assertEquals(0, result.exitCode());
        assertFalse(result.timedOut());
        var catalog = new ObjectMapper().readTree(result.stdout());
        assertEquals("CATALOG_DESCRIBED", catalog.path("type").asString());
        assertEquals(128 * 1024, catalog.path("padding").asString().length());
        assertEquals(64 * 1024, result.stderr().length());
        runner.requireRunProtocol();
    }

    @Test
    void legacyProtocolTwoCannotClaimImportedSnapshotSupport() throws Exception {
        var runner = runner();
        runner.requireRunProtocol();
        org.junit.jupiter.api.Assertions.assertThrows(
            WorkerProcessRunner.ProtocolMismatchException.class,
            () -> runner.requireRunProtocol(true));
    }

    @Test
    void ordinaryWorkerOutputRetainsDiagnosticLimit() throws Exception {
        var result = runner().run("--validate-driver", java.util.Map.of(),
            Duration.ofSeconds(10));
        assertEquals(0, result.exitCode());
        assertEquals(64 * 1024, result.stdout().length());
        assertEquals(64 * 1024, result.stderr().length());
    }

    private WorkerProcessRunner runner() throws Exception {
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS,
            CatalogWorker.class.getName());
        Path jar = directory.resolve("worker.jar");
        String resource = CatalogWorker.class.getName().replace('.', '/') + ".class";
        try (var output = new JarOutputStream(Files.newOutputStream(jar), manifest);
            var input = CatalogWorker.class.getResourceAsStream("/" + resource)) {
            output.putNextEntry(new JarEntry(resource));
            input.transferTo(output);
            output.closeEntry();
        }
        var properties = new DriverWorkerProperties();
        properties.setExecutable(jar);
        return new WorkerProcessRunner(properties, new ObjectMapper());
    }

    public static final class CatalogWorker {

        public static void main(String[] args) throws Exception {
            System.in.readAllBytes();
            System.out.println("{\"type\":\"CATALOG_DESCRIBED\",\"protocolVersion\":2,"
                + "\"padding\":\"" + "x".repeat(128 * 1024) + "\"}");
            System.err.print("d".repeat(80 * 1024));
        }
    }
}
