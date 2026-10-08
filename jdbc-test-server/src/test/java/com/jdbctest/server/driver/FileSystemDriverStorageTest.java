package com.jdbctest.server.driver;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

class FileSystemDriverStorageTest {

  @TempDir
  Path temporaryDirectory;

  @Test
  void storesMultipleJarsWithStableFingerprintAndGeneratedPaths() throws Exception {
    FileSystemDriverStorage storage = storage(20, 10_000, 20_000);
    DriverStorage.StagedDriverPackage first = storage.stage(
        List.of(jar("../../vendor.jar", "com.acme.Driver\n"), jar("common.jar", null)));
    DriverStorage.StagedDriverPackage reordered = storage.stage(
        List.of(jar("common.jar", null), jar("vendor.jar", "com.acme.Driver\n")));
    assertEquals(DriverPackageInspector.fingerprint(first.artifacts()),
        DriverPackageInspector.fingerprint(reordered.artifacts()));
    assertEquals(java.util.Set.of("com.acme.Driver"),
        DriverPackageInspector.detectDriverClasses(first));
    List<DriverStorage.StoredArtifact> saved = storage.moveToDriver(first, UUID.randomUUID());
    assertEquals(2, saved.size());
    assertTrue(saved.stream()
        .allMatch(item -> item.storageKey().matches("drivers/[0-9a-f-]+/[0-9a-f-]+\\.jar")));
    assertTrue(saved.stream().noneMatch(item -> item.storageKey().contains("vendor")));
    assertTrue(
        saved.stream().allMatch(item -> Files.isRegularFile(storage.resolve(item.storageKey()))));
    storage.deleteStaging(first);
    storage.deleteStaging(reordered);
  }

  @Test
  void rejectsInvalidJarAndEnforcesLimitsAndCleansStaging() throws Exception {
    FileSystemDriverStorage storage = storage(1, 5, 6);
    DriverException invalid = assertThrows(DriverException.class, () -> storage.stage(List.of(
        new MockMultipartFile("files", "not.jar", "application/octet-stream",
            "bad".getBytes(StandardCharsets.UTF_8)))));
    assertEquals("DRIVER_ARTIFACT_INVALID", invalid.code());
    DriverException tooLarge = assertThrows(DriverException.class, () -> storage.stage(List.of(
        new MockMultipartFile("files", "large.jar", "application/java-archive", new byte[6]))));
    assertEquals("DRIVER_ARTIFACT_TOO_LARGE", tooLarge.code());
    Path staging = temporaryDirectory.resolve("staging");
    if (Files.exists(staging)) {
      try (var directories = Files.list(staging)) {
        assertTrue(directories.findAny().isEmpty());
      }
    }
  }

  @Test
  void rejectsNullArtifactAndCleansPartiallyStagedPackage() throws Exception {
    FileSystemDriverStorage storage = storage(20, 10_000, 20_000);
    DriverException exception = assertThrows(DriverException.class,
        () -> storage.stage(java.util.Arrays.asList(jar("valid.jar", null), null)));
    assertEquals("DRIVER_ARTIFACT_INVALID", exception.code());
    try (var directories = Files.list(temporaryDirectory.resolve("staging"))) {
      assertTrue(directories.findAny().isEmpty());
    }
  }

  @Test
  void spiParserIgnoresCommentsBlankLinesAndDuplicates() throws Exception {
    FileSystemDriverStorage storage = storage(20, 10_000, 20_000);
    DriverStorage.StagedDriverPackage staged = storage.stage(List.of(
        jar("driver.jar", " # comment\n com.acme.One # inline\n\ncom.acme.One\n com.acme.Two \n")));
    assertEquals(java.util.Set.of("com.acme.One", "com.acme.Two"),
        DriverPackageInspector.detectDriverClasses(staged));
    storage.deleteStaging(staged);
  }

  private FileSystemDriverStorage storage(int count, long fileSize, long packageSize) {
    DriverStorageProperties properties = new DriverStorageProperties();
    properties.setDataDirectory(temporaryDirectory);
    properties.setMaxArtifacts(count);
    properties.setMaxFileSize(fileSize);
    properties.setMaxPackageSize(packageSize);
    return new FileSystemDriverStorage(properties);
  }

  private static MockMultipartFile jar(String name, String spi) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (JarOutputStream jar = new JarOutputStream(bytes)) {
      if (spi != null) {
        jar.putNextEntry(new JarEntry("META-INF/services/java.sql.Driver"));
        jar.write(spi.getBytes(StandardCharsets.UTF_8));
        jar.closeEntry();
      } else {
        jar.putNextEntry(new JarEntry("empty"));
        jar.closeEntry();
      }
    }
    return new MockMultipartFile("files", name, "application/java-archive", bytes.toByteArray());
  }
}
