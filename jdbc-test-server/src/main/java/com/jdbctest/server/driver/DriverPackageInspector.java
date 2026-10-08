package com.jdbctest.server.driver;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarFile;

final class DriverPackageInspector {

  private DriverPackageInspector() {
  }

  static Set<String> detectDriverClasses(DriverStorage.StagedDriverPackage staged) {
    Set<String> detected = new LinkedHashSet<>();
    for (DriverStorage.StagedArtifact artifact : staged.artifacts()) {
      try (JarFile jar = new JarFile(artifact.path().toFile(), false)) {
        var entry = jar.getJarEntry("META-INF/services/java.sql.Driver");
        if (entry == null) {
          continue;
        }
        try (var input = jar.getInputStream(entry); var reader = new BufferedReader(
            new InputStreamReader(input, StandardCharsets.UTF_8))) {
          reader.lines().map(
              line -> line.substring(0, line.indexOf('#') < 0 ? line.length() : line.indexOf('#'))
                  .trim()).filter(line -> !line.isEmpty()).forEach(detected::add);
        }
      } catch (IOException exception) {
        throw new DriverException(org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT,
            "DRIVER_ARTIFACT_INVALID", "Unable to inspect driver artifact");
      }
    }
    return detected;
  }

  static String fingerprint(List<DriverStorage.StagedArtifact> items) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      items.stream().map(DriverStorage.StagedArtifact::sha256).sorted(Comparator.naturalOrder())
          .forEach(value -> digest.update((value + "\n").getBytes(StandardCharsets.UTF_8)));
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
