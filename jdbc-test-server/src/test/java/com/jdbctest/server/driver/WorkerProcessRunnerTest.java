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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"timeout", "interrupt", "shutdown"})
    void processLifecycleReclaimsTheProcessAndAdmission(String outcome) throws Exception {
        var runner = runner();
        Path marker = directory.resolve("started");
        var result = new java.util.concurrent.FutureTask<>(() -> runner.run("--hang", marker.toString(),
            Duration.ofSeconds(outcome.equals("timeout") ? 2 : 20)));
        Thread thread = Thread.ofVirtual().start(result);
        try (var watcher = directory.getFileSystem().newWatchService()) {
            directory.register(watcher, java.nio.file.StandardWatchEventKinds.ENTRY_CREATE);
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (!Files.exists(marker) && System.nanoTime() < deadline) {
                var key = watcher.poll(1, java.util.concurrent.TimeUnit.SECONDS);
                if (key != null) {
                    key.pollEvents();
                    key.reset();
                }
            }
            org.junit.jupiter.api.Assertions.assertTrue(Files.exists(marker));
            var pids = Files.readAllLines(marker).stream().map(Long::parseLong).toList();
            if (outcome.equals("interrupt")) {
                thread.interrupt();
                org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> result.get(5, java.util.concurrent.TimeUnit.SECONDS));
            } else {
                if (outcome.equals("shutdown")) {
                    runner.shutdown();
                }
                var response = result.get(5, java.util.concurrent.TimeUnit.SECONDS);
                assertEquals(outcome.equals("timeout"), response.timedOut());
            }
            for (long pid : pids) {
                assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
            }
            if (!outcome.equals("shutdown")) {
                assertEquals(0, runner.run("--validate-driver", java.util.Map.of(), Duration.ofSeconds(5)).exitCode());
            } else {
                org.junit.jupiter.api.Assertions.assertEquals("WORKER_BUSY",
                    org.junit.jupiter.api.Assertions.assertThrows(DriverException.class,
                        () -> runner.run("--validate-driver", java.util.Map.of(), Duration.ofSeconds(5))).code());
            }
        } finally {
            runner.shutdown();
            thread.interrupt();
            thread.join(5_000);
        }
    }

    @Test
    void missingExecutableDoesNotLeakAdmission() throws Exception {
        var properties = new DriverWorkerProperties();
        properties.setMaxConcurrentShortWorkers(1);
        properties.setMaxPendingShortWorkers(0);
        var runner = new WorkerProcessRunner(properties, new ObjectMapper());
        try {
            for (int attempt = 0; attempt < 2; attempt++) {
                org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                    () -> runner.run("--validate-driver", java.util.Map.of(), Duration.ofSeconds(5)));
            }
            runner();
            properties.setExecutable(directory.resolve("worker.jar"));
            assertEquals(0, runner.run("--validate-driver", java.util.Map.of(), Duration.ofSeconds(5)).exitCode());
        } finally {
            runner.shutdown();
        }
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
        properties.setMaxConcurrentShortWorkers(1);
        properties.setMaxPendingShortWorkers(0);
        return new WorkerProcessRunner(properties, new ObjectMapper());
    }

    public static final class CatalogWorker {

        public static void main(String[] args) throws Exception {
            if (args[0].equals("--child")) {
                new java.util.concurrent.CountDownLatch(1).await();
                return;
            }
            byte[] request = System.in.readAllBytes();
            if (args[0].equals("--hang")) {
                String json = new String(request, java.nio.charset.StandardCharsets.UTF_8);
                Path marker = Path.of(json.substring(1, json.length() - 1));
                Path partial = marker.resolveSibling("started.partial");
                var child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", System.getProperty("java.class.path"), CatalogWorker.class.getName(), "--child").start();
                Files.writeString(partial, ProcessHandle.current().pid() + "\n" + child.pid());
                Files.move(partial, marker, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                new java.util.concurrent.CountDownLatch(1).await();
                return;
            }
            System.out.println("{\"type\":\"CATALOG_DESCRIBED\",\"protocolVersion\":2,"
                + "\"padding\":\"" + "x".repeat(128 * 1024) + "\"}");
            System.err.print("d".repeat(80 * 1024));
        }
    }
}
