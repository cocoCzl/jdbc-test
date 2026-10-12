package com.jdbctest.server.deploy;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LaunchProcessesTest {
  @TempDir Path directory;
  private List<String> command(String mode, Path directory) {
    return List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
        Path.of("target/test-classes").toAbsolutePath().toString(), LaunchTreeProbe.class.getName(), mode, directory.toString());
  }
  @Test void closingStopsOwnedTreeAndRetainsUnrelatedProcess() throws Exception {
    Path unrelatedDir = Files.createDirectory(directory.resolve("unrelated"));
    Process unrelated = new ProcessBuilder(command("unrelated", unrelatedDir)).start();
    try {
      long child;
      Process parent;
      try (var processes = new LaunchProcesses()) {
        parent = processes.start(command("parent", directory), directory, LaunchProcesses.basicEnvironment());
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
          while (!Files.exists(directory.resolve("child.pid"))) Thread.sleep(20);
        });
        child = Long.parseLong(Files.readString(directory.resolve("child.pid")));
        Thread.sleep(100); // tracker has observed the complete launched tree
      }
      assertFalse(parent.isAlive());
      assertFalse(ProcessHandle.of(child).map(ProcessHandle::isAlive).orElse(false));
      assertTrue(unrelated.isAlive());
    } finally { unrelated.destroyForcibly(); unrelated.waitFor(); }
  }
  @Test void buildFailurePropagatesWithoutStartingService() throws Exception {
    try (var processes = new LaunchProcesses()) {
      assertThrows(Exception.class, () -> processes.run(command("bad-mode", directory).subList(0, 4), directory, LaunchProcesses.basicEnvironment()));
    }
  }
}
