package com.jdbctest.worker;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;

class WorkerMainTest {

  @Test
  void runtimeConfigPreservesLiteralCredentialsAndCustomProviders(
      @org.junit.jupiter.api.io.TempDir Path directory) throws Exception {
    var provider = new com.jdbctest.catalog.DataSourceProviders.Provider(
        "custom.jdbc.Source", "jdbcUrl", "username", "secret");
    var providers = new com.jdbctest.catalog.DataSourceProviders(provider, null, null);
    String password = " fixture-${MISSING}-$\\中文-password ";
    var request = new WorkerRunRequest(2, "fixture",
        new WorkerRunRequest.DriverRequest("custom.jdbc.Driver", List.of(), providers),
        new WorkerRunRequest.ConnectionRequest("jdbc:fixture:test", "tester", password,
            java.util.Map.of(), "postgresql"), null, directory.toString(), 1000L,
        new com.jdbctest.catalog.ExecutionScope("fixture", "owned_scope"),
        "j01234567890123456789", providers.capabilities(
            com.jdbctest.catalog.AdapterExecutionConfig.load("postgresql").capabilities()), true);
    Path config = directory.resolve("config.json");
    var method = WorkerMain.class.getDeclaredMethod("writeConfig", Path.class, WorkerRunRequest.class);
    method.setAccessible(true);
    method.invoke(new WorkerMain(), config, request);
    String original = System.getProperty("config.yaml");
    try {
      System.setProperty("config.yaml", config.toString());
      var loaded = com.jdbctest.config.ConfigLoader.load();
      assertEquals(password, loaded.db.password);
      assertEquals(providers, loaded.db.dataSources);
    } finally {
      if (original == null) {
        System.clearProperty("config.yaml");
      } else {
        System.setProperty("config.yaml", original);
      }
    }
  }

  @Test
  void catalogSmokeLoadsPackagedScenarios() {
    assertEquals(0, new WorkerMain().run(new String[] {"--catalog-smoke"}));
  }

  @Test
  void incompatibleOrMissingRequestProtocolIsRejectedBeforeDriverOrWorkspace() throws Exception {
    for (String version : List.of("1", "null", "3")) {
      Path workspace = Files.createTempDirectory("protocol-rejection-").resolve("must-not-exist");
      Process process = new ProcessBuilder("java", "-cp", System.getProperty("java.class.path"),
          WorkerMain.class.getName()).start();
      String request = "{\"protocolVersion\":" + version + ",\"workspace\":\"" + workspace + "\"}";
      process.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
      process.getOutputStream().close();
      assertTrue(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS));
      assertNotEquals(0, process.exitValue());
      assertTrue(
          new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).contains(
              "WORKER_PROTOCOL_MISMATCH"));
      assertFalse(Files.exists(workspace));
    }
  }

  @Test
  void sanitizerRemovesCredentialValues() {
    assertEquals("jdbc:x?password=[REDACTED]",
        CredentialSanitizer.sanitize("jdbc:x?password=secret"));
    assertFalse(CredentialSanitizer.sanitize("token:abc").contains("abc"));
  }

  @Test
  void missingDriverArtifactIsRejected() {
    IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
        () -> DriverLoader.load(
            new WorkerRunRequest.DriverRequest("x.Driver", List.of("/missing/driver.jar"))));
    assertTrue(error.getMessage().contains("does not exist"));
  }

  @Test
  void nonDriverClassIsRejected() {
    Path workerJar = Path.of("target", "jdbc-test-worker-1.2.1-SNAPSHOT-all.jar");
    if (!workerJar.toFile().isFile()) {
      return;
    }
    IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
        () -> DriverLoader.load(new WorkerRunRequest.DriverRequest("com.jdbctest.worker.WorkerMain",
            List.of(workerJar.toString()))));
    assertTrue(error.getMessage().contains("java.sql.Driver"));
  }

  @Test
  void validationModeLoadsExternalPostgresDriverAndKeepsStdoutAsNdjson() throws Exception {
    Path postgresJar = Path.of(
        org.postgresql.Driver.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    Process process = new ProcessBuilder("java", "-cp", System.getProperty("java.class.path"),
        WorkerMain.class.getName(), "--validate-driver").start();
    String request =
        "{\"driverClass\":\"org.postgresql.Driver\",\"artifacts\":[\"" + postgresJar + "\"]}";
    process.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
    process.getOutputStream().close();
    assertTrue(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS));
    String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertEquals(0, process.exitValue());
    assertTrue(stdout.contains("DRIVER_VALIDATED"));
    assertTrue(java.util.Arrays.stream(stdout.split("\\R")).filter(line -> !line.isBlank())
        .allMatch(line -> line.startsWith("{")));
  }

  @Test
  void probeModeKeepsProtocolStdoutCleanWhenDriverPrints() throws Exception {
    Path artifact = pollutingDriverJar();
    Process process = new ProcessBuilder("java", "-cp", System.getProperty("java.class.path"),
        WorkerMain.class.getName(), "--probe-connection").start();
    String request =
        "{\"driver\":{\"driverClass\":\"polluting.Driver\",\"artifacts\":[\"" + artifact
            + "\"]},\"connection\":{\"jdbcUrl\":\"jdbc:polluting:test\",\"username\":\"u\",\"password\":\"secret\"}}";
    process.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
    process.getOutputStream().close();
    assertTrue(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS));
    String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
    assertEquals(DriverMainExit.DRIVER_FAILURE, process.exitValue());
    assertTrue(stdout.contains("DRIVER_DOES_NOT_ACCEPT_URL"));
    assertTrue(java.util.Arrays.stream(stdout.split("\\R")).filter(line -> !line.isBlank())
        .allMatch(line -> line.startsWith("{")));
    assertFalse(stdout.contains("driver-output"));
    assertFalse(stderr.contains("secret"));
  }

  private static Path pollutingDriverJar() throws Exception {
    Path root = Files.createTempDirectory("polluting-driver-");
    Path source = root.resolve("polluting/Driver.java");
    Files.createDirectories(source.getParent());
    Files.writeString(source,
        "package polluting; public class Driver implements java.sql.Driver { static { System.out.println(\"driver-output-static\"); } public Driver(){System.out.println(\"driver-output-constructor\");} public java.sql.Connection connect(String u, java.util.Properties p){System.out.println(\"driver-output-connect\"); return null;} public boolean acceptsURL(String u){return true;} public java.sql.DriverPropertyInfo[] getPropertyInfo(String u, java.util.Properties p){return new java.sql.DriverPropertyInfo[0];} public int getMajorVersion(){return 1;} public int getMinorVersion(){return 0;} public boolean jdbcCompliant(){return false;} public java.util.logging.Logger getParentLogger(){return java.util.logging.Logger.getGlobal();} }");
    assertEquals(0, ToolProvider.getSystemJavaCompiler()
        .run(null, null, null, "-d", root.toString(), source.toString()));
    Path jar = root.resolve("driver.jar");
    try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
      output.putNextEntry(new JarEntry("polluting/Driver.class"));
      output.write(Files.readAllBytes(root.resolve("polluting/Driver.class")));
      output.closeEntry();
    }
    return jar;
  }

  private static final class DriverMainExit {

    private static final int DRIVER_FAILURE = 3;
  }
}
