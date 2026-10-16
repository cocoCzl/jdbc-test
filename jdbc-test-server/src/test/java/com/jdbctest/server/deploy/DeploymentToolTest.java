package com.jdbctest.server.deploy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeploymentToolTest {

    @TempDir
    Path directory;

    @Test
    void unsupportedPasswordCharactersAreRejectedBeforeEncoding() throws Exception {
        assertThrows(Exception.class, () -> DeploymentTool.dotenv("password\n"));
        assertThrows(Exception.class, () -> DeploymentTool.dotenv("password\0"));
    }

    @Test
    void atomicPublicationNeverOverwritesConfigurationOrSymlinks() throws Exception {
        Path target = directory.resolve("application.properties");
        DeploymentTool.publish(target, "original-key");
        assertThrows(Exception.class, () -> DeploymentTool.publish(target, "replacement-key"));
        assertEquals("original-key", Files.readString(target));
        if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
            assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(
                Files.getPosixFilePermissions(target)));
            Path symlink = directory.resolve("symlink.properties");
            Files.createSymbolicLink(symlink, target);
            assertThrows(Exception.class, () -> DeploymentTool.publish(symlink, "replacement-key"));
        }
        try (var contents = Files.list(directory)) {
            assertFalse(
                contents.anyMatch(path -> path.getFileName().toString().startsWith(".config-")));
        }
    }

    @Test
    void concurrentPublicationHasExactlyOneWinner() throws Exception {
        Path target = directory.resolve("concurrent.properties");
        var ready = new java.util.concurrent.CountDownLatch(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var results = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
            for (String value : java.util.List.of("first", "second")) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        DeploymentTool.publish(target, value);
                        return true;
                    } catch (Exception expected) {
                        return false;
                    }
                }));
            }
            ready.await();
            start.countDown();
            assertEquals(1, results.stream().map(result -> {
                try {
                    return result.get() ? 1 : 0;
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            }).mapToInt(Integer::intValue).sum());
            org.junit.jupiter.api.Assertions.assertTrue(
                java.util.List.of("first", "second").contains(Files.readString(target)));
        }
    }
}
