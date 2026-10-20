package com.jdbctest.server.run;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/**
 * Packages the test fixture with the test runtime classpath for ProcessBuilder-based tests.
 */
final class FakeWorkerJar {

    private FakeWorkerJar() {
    }

    static Path create(Path directory) throws IOException, URISyntaxException {
        return create(directory, 2);
    }

    static Path create(Path directory, int protocolVersion) throws IOException, URISyntaxException {
        Path classes = Path.of(
            FakeWorkerMain.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path jar = directory.resolve("fake-worker.jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes()
            .put(Attributes.Name.MAIN_CLASS, FakeWorkerMain.class.getName());
        manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, java.util.Arrays.stream(
                System.getProperty("java.class.path").split(java.io.File.pathSeparator))
            .map(item -> Path.of(item).toUri().toString())
            .collect(java.util.stream.Collectors.joining(" ")));
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            Path file = classes.resolve("com/jdbctest/server/run/FakeWorkerMain.class");
            output.putNextEntry(
                new java.util.jar.JarEntry("com/jdbctest/server/run/FakeWorkerMain.class"));
            output.write(Files.readAllBytes(file));
            output.closeEntry();
            var catalog = com.jdbctest.catalog.TestCatalog.fromScenarioInventory(
                Path.of("..", "compatibility", "v1", "scenario-inventory.yaml"),
                Path.of("..", "jdbc-test-suite", "src", "main", "java"));
            var scenarios = catalog.testCases().stream().map(
                item -> java.util.Map.of("id", item.scenarioId(), "moduleId", item.module().id(),
                    "displayName", item.contract(), "profile", item.profile().name())).toList();
            var modules = catalog.modules().stream().map(
                item -> java.util.Map.of("id", item.id(), "name", item.displayName(), "description",
                    item.description(), "scenarioCount",
                    catalog.testCases().stream()
                        .filter(test -> test.module().id().equals(item.id()))
                        .count(), "coreScenarioCount", catalog.testCases().stream().filter(
                        test -> test.module().id().equals(item.id()) && test.profile().name()
                            .equals("CORE")).count())).toList();
            var payload = java.util.Map.of("type", "CATALOG_DESCRIBED", "protocolVersion",
                protocolVersion, "adapterPackageVersion", 1, "runId", "catalog", "profiles", java.util.List.of(
                    java.util.Map.of("id", "CORE", "name", "Core", "scenarioCount",
                        catalog.select(com.jdbctest.catalog.TestSelection.core()).size()),
                    java.util.Map.of("id", "FULL", "name", "Full", "scenarioCount",
                        scenarios.size())),
                "modules", modules, "scenarios", scenarios, "totalScenarioCount", scenarios.size());
            output.putNextEntry(new java.util.jar.JarEntry("fake-catalog.json"));
            output.write(new tools.jackson.databind.ObjectMapper().writeValueAsBytes(payload));
            output.closeEntry();
        }
        return jar;
    }
}
