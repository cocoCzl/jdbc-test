package com.jdbctest.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AdapterPackageSnapshotTest {

    @TempDir
    Path directory;

    private String manifest() throws Exception {
        try (var input = getClass().getResourceAsStream("/adapters/postgresql/adapter.json")) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8)
                .replace("\"trust\": \"official\"", "\"trust\": \"local\"");
        }
    }

    private Map<String, String> files() {
        Map<String, String> files = new LinkedHashMap<>();
        var definition = AdapterRegistry.bundled().required("postgresql");
        for (String area : definition.assets().areas()) {
            files.put("ddl/postgresql/" + area + "_ddl.sql", "-- synthetic ddl\n");
            files.put("dml/postgresql/" + area + "_dml.sql", "-- synthetic dml\n");
        }
        files.put("pool/postgresql.yaml", "{}\n");
        return files;
    }

    private AdapterPackageSnapshot read(String manifest, Map<String, String> files) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("adapter.json"));
            zip.write(manifest.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            for (var entry : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return AdapterPackageSnapshot.readZip(new ByteArrayInputStream(output.toByteArray()));
    }

    @Test
    void freezesBytesAndChecksFingerprintBeforeMaterializing() throws Exception {
        var snapshot = read(manifest(), files());
        Path target = directory.resolve("assets");
        snapshot.materialize(target);
        assertEquals("-- synthetic ddl\n", Files.readString(
            target.resolve("ddl/postgresql/connection_ddl.sql")));
        assertEquals(64, snapshot.fingerprint().length());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.resources().clear());
        var changed = new LinkedHashMap<>(snapshot.resources());
        changed.put("pool/postgresql.yaml", Base64.getEncoder().encodeToString("changed".getBytes()));
        assertThrows(IllegalArgumentException.class,
            () -> new AdapterPackageSnapshot(snapshot.manifest(), changed, snapshot.fingerprint()));
    }

    @Test
    void rejectsMissingResourcesTraversalExecutableUnsupportedDialectAndZipBomb() throws Exception {
        for (String name : java.util.List.of("../escape.sql", "/absolute.sql", "ddl/../escape.sql",
            "ddl\\escape.sql", "ddl/postgresql/plugin.jar")) {
            var invalid = files();
            invalid.put(name, "invalid");
            assertThrows(IllegalArgumentException.class, () -> read(manifest(), invalid), name);
        }
        var missing = files();
        missing.remove("ddl/postgresql/connection_ddl.sql");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> read(manifest(), missing))
            .getMessage().contains("ddl/postgresql/connection_ddl.sql"));
        assertThrows(IllegalArgumentException.class, () -> read(manifest()
            .replace("\"dialect\": \"postgresql\"", "\"dialect\": \"unsupported\""), files()));
        assertThrows(IllegalArgumentException.class, () -> read("{}", files()));
        var bomb = files();
        bomb.put("ddl/postgresql/bomb.sql", "x".repeat(1_048_577));
        assertThrows(IllegalArgumentException.class, () -> read(manifest(), bomb));
    }

    @Test
    void cliExportAndJavaImportInterpretTheSameCoreDeclaration() throws Exception {
        Path root = Path.of("..").toAbsolutePath().normalize();
        Path archive = directory.resolve("adapter.zip");
        Process process = new ProcessBuilder("python3", "-B", "scripts/runner.py", "pack-adapter",
            "--adapter", "examples/adapters/postgresql-local", "--output", archive.toString())
            .directory(root.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
        try (var input = Files.newInputStream(archive)) {
            var snapshot = AdapterPackageSnapshot.readZip(input);
            try (var manifestInput = Files.newInputStream(
                root.resolve("examples/adapters/postgresql-local/adapter.json"))) {
                var original = AdapterManifest.read(manifestInput);
                assertEquals(original, snapshot.definition());
                assertEquals(original.execution(), snapshot.definition().execution());
            }
        }
    }
}
