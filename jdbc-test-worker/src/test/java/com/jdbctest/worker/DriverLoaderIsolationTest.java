package com.jdbctest.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Synthetic driver packages only; proves selection/isolation, not PostgreSQL compatibility.
 */
class DriverLoaderIsolationTest {
  @TempDir
  Path directory;

  @Test
  void sameClassNameInDifferentPackagesCannotFallBackToParentOrOtherVersion() throws Exception {
    Path first = driverJar("first", 11);
    Path second = driverJar("second", 22);
    assertNotEquals(digest(first), digest(second));
    try (var one = DriverLoader.load(new WorkerRunRequest.DriverRequest(
        "org.postgresql.Driver", List.of(first.toString())));
        var two = DriverLoader.load(new WorkerRunRequest.DriverRequest(
            "org.postgresql.Driver", List.of(second.toString())))) {
      assertEquals(11, one.driver().getMajorVersion());
      assertEquals(22, two.driver().getMajorVersion());
      assertNotSame(one.driver().getClass(), two.driver().getClass());
      assertNotSame(org.postgresql.Driver.class, one.driver().getClass());
      assertSame(ClassLoader.getPlatformClassLoader(), one.classLoader().getParent());
      assertEquals(first.toUri(), one.driver().getClass().getProtectionDomain()
          .getCodeSource().getLocation().toURI());
      assertEquals(second.toUri(), two.driver().getClass().getProtectionDomain()
          .getCodeSource().getLocation().toURI());
    }
  }

  private Path driverJar(String name, int major) throws Exception {
    Path root = Files.createDirectory(directory.resolve(name));
    Path source = root.resolve("org/postgresql/Driver.java");
    Files.createDirectories(source.getParent());
    Files.writeString(source, """
        package org.postgresql;
        public class Driver implements java.sql.Driver {
          public java.sql.Connection connect(String url, java.util.Properties properties) {
            return null;
          }
          public boolean acceptsURL(String url) { return false; }
          public java.sql.DriverPropertyInfo[] getPropertyInfo(String u, java.util.Properties p) {
            return new java.sql.DriverPropertyInfo[0];
          }
          public int getMajorVersion() { return %d; }
          public int getMinorVersion() { return 0; }
          public boolean jdbcCompliant() { return false; }
          public java.util.logging.Logger getParentLogger() {
            return java.util.logging.Logger.getGlobal();
          }
        }
        """.formatted(major));
    assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
        "--release", "21", "-d", root.toString(), source.toString()));
    Path jar = root.resolve("driver.jar");
    try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
      output.putNextEntry(new JarEntry("org/postgresql/Driver.class"));
      output.write(Files.readAllBytes(root.resolve("org/postgresql/Driver.class")));
      output.closeEntry();
    }
    return jar;
  }

  private String digest(Path path) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest(Files.readAllBytes(path)));
  }
}
