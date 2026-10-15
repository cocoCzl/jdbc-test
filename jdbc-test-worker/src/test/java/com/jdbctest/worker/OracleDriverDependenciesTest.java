package com.jdbctest.worker;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdbctest.extension.OracleDriverDependencies;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Synthetic split packages: dependency diagnostics and isolation, not Oracle compatibility. */
class OracleDriverDependenciesTest {

  @TempDir
  Path directory;

  @Test
  void validationWarnsForBareOracleButAcceptsCompleteMultiJarPackage() throws Exception {
    var packages = packages();
    var bare = validate(List.of(packages.getFirst()));
    assertEquals(List.of("ORACLE_I18N_MISSING", "ORACLE_XML_MISSING"), bare);
    assertEquals(List.of(), validate(packages));
    try (var loaded = DriverLoader.load(new WorkerRunRequest.DriverRequest(
        "oracle.jdbc.OracleDriver", packages.stream().map(Path::toString).toList()))) {
      assertSame(ClassLoader.getPlatformClassLoader(), loaded.classLoader().getParent());
      assertThrows(ClassNotFoundException.class,
          () -> Class.forName("oracle.xdb.XMLType", false, ClassLoader.getSystemClassLoader()));
      assertSame(loaded.classLoader(),
          Class.forName("oracle.xdb.XMLType", false, loaded.classLoader()).getClassLoader());
    }
  }

  @Test
  void requiredLinkageFailureIsReportedAsValidationFailure() throws Exception {
    var response = validate(List.of(packages().getFirst()), "oracle.jdbc.BrokenDriver", 3);
    assertEquals("DRIVER_VALIDATION_FAILED", response.path("type").asText());
    assertEquals("DRIVER_FAILURE", response.path("errorType").asText());
  }

  @Test
  void charsetIsCheckedByConversionAndOptionalComponentsAreNotAlwaysMandatory() throws Exception {
    var packages = packages();
    try (var bare = loader(List.of(packages.getFirst()));
        var complete = loader(packages)) {
      assertDoesNotThrow(() -> OracleDriverDependencies.preflight(connection(873), bare, false));
      var error = assertThrows(IllegalStateException.class,
          () -> OracleDriverDependencies.preflight(connection(852), bare, false));
      assertTrue(error.getMessage().contains("orai18n.jar"));
      assertInstanceOf(SQLException.class, error.getCause());
      assertDoesNotThrow(
          () -> OracleDriverDependencies.preflight(connection(852), complete, true));
      var connection = connection(873);
      assertTrue(assertThrows(IllegalStateException.class,
          () -> OracleDriverDependencies.preflight(connection, bare, true))
          .getMessage().contains("ORACLE_XML_MISSING"));
      verify(connection, never()).createStatement();
    }
  }

  @Test
  void incompleteXmlAndUnreadableCharsetsCannotPassPreflight() throws Exception {
    var packages = packages();
    try (var incomplete = loader(packages.subList(0, 3));
        var complete = loader(packages)) {
      // XMLType exists but its method signature references a missing parser class.
      assertEquals(List.of("ORACLE_XML_MISSING"), OracleDriverDependencies.warnings(
          "oracle.jdbc.OracleDriver", incomplete));
      assertEquals(List.of(), OracleDriverDependencies.warnings("other.Driver", incomplete));
      var denied = mock(Connection.class);
      when(denied.createStatement()).thenThrow(new SQLException("fixture denied"));
      assertTrue(assertThrows(IllegalStateException.class,
          () -> OracleDriverDependencies.preflight(denied, complete, false))
          .getMessage().contains("ORACLE_CHARSET_PREFLIGHT_FAILED"));
      var empty = connection(873);
      when(empty.createStatement().executeQuery(anyString()).next()).thenReturn(false);
      assertThrows(IllegalStateException.class,
          () -> OracleDriverDependencies.preflight(empty, complete, false));
    }
  }

  private Connection connection(int charset) throws Exception {
    var connection = mock(Connection.class);
    var statement = mock(Statement.class);
    var rows = mock(ResultSet.class);
    when(connection.createStatement()).thenReturn(statement);
    when(statement.executeQuery(anyString())).thenReturn(rows);
    when(rows.next()).thenReturn(true, true, false);
    when(rows.getInt(1)).thenReturn(charset, 2000);
    return connection;
  }

  private URLClassLoader loader(List<Path> paths) throws Exception {
    var urls = new ArrayList<URL>();
    for (Path path : paths) {
      urls.add(path.toUri().toURL());
    }
    return new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
  }

  private List<String> validate(List<Path> paths) throws Exception {
    return new ObjectMapper().convertValue(
        validate(paths, "oracle.jdbc.OracleDriver", 0).path("warnings"),
        new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
  }

  private com.fasterxml.jackson.databind.JsonNode validate(List<Path> paths, String driverClass,
      int exitCode) throws Exception {
    var mapper = new ObjectMapper();
    var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java")
        .toString(), "-cp", System.getProperty("java.class.path"), WorkerMain.class.getName(),
        "--validate-driver").start();
    try {
      mapper.writeValue(process.getOutputStream(), Map.of("driverClass", driverClass,
          "artifacts", paths.stream().map(Path::toString).toList()));
      process.getOutputStream().close();
      assertTrue(process.waitFor(30, TimeUnit.SECONDS));
      assertEquals(exitCode, process.exitValue());
      var response = mapper.readTree(new String(process.getInputStream().readAllBytes(),
          StandardCharsets.UTF_8));
      assertEquals(exitCode == 0 ? "DRIVER_VALIDATED" : "DRIVER_VALIDATION_FAILED",
          response.path("type").asText());
      return response;
    } finally {
      if (process.isAlive()) {
        process.destroyForcibly();
        process.waitFor(10, TimeUnit.SECONDS);
      }
    }
  }

  private List<Path> packages() throws Exception {
    Path root = Files.createTempDirectory(directory, "synthetic-");
    var sources = Map.of(
        "oracle.jdbc.OracleDriver", """
            package oracle.jdbc;
            public class OracleDriver implements java.sql.Driver {
              public java.sql.Connection connect(String u, java.util.Properties p) { return null; }
              public boolean acceptsURL(String u) { return false; }
              public java.sql.DriverPropertyInfo[] getPropertyInfo(String u, java.util.Properties p) {
                return new java.sql.DriverPropertyInfo[0];
              }
              public int getMajorVersion() { return 21; }
              public int getMinorVersion() { return 13; }
              public boolean jdbcCompliant() { return true; }
              public java.util.logging.Logger getParentLogger() {
                return java.util.logging.Logger.getGlobal();
              }
            }
            """,
        "oracle.jdbc.BrokenDriver", """
            package oracle.jdbc;
            public class BrokenDriver extends OracleDriver {
              public int getMajorVersion() { return new oracle.xml.parser.v2.DOMParser().hashCode(); }
            }
            """,
        "oracle.sql.CharacterSet", """
            package oracle.sql;
            public class CharacterSet {
              private final int id;
              private CharacterSet(int id) { this.id = id; }
              public static CharacterSet make(int id) { return new CharacterSet(id); }
              public byte[] convert(String value) throws java.sql.SQLException {
                if (id == 852) {
                  try { Class.forName("oracle.i18n.text.converter.CharacterConverterOGS"); }
                  catch (ClassNotFoundException e) { throw new java.sql.SQLException("missing", e); }
                }
                return value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
              }
            }
            """,
        "oracle.i18n.text.converter.CharacterConverterOGS",
        "package oracle.i18n.text.converter; public class CharacterConverterOGS {}",
        "oracle.xdb.XMLType", """
            package oracle.xdb;
            public class XMLType { public oracle.xml.parser.v2.DOMParser parser() { return null; } }
            """,
        "oracle.xml.parser.v2.DOMParser", "package oracle.xml.parser.v2; public class DOMParser {}");
    var arguments = new ArrayList<>(List.of("--release", "21", "-d", root.toString()));
    for (var entry : sources.entrySet()) {
      Path source = root.resolve(entry.getKey().replace('.', '/') + ".java");
      Files.createDirectories(source.getParent());
      Files.writeString(source, entry.getValue());
      arguments.add(source.toString());
    }
    assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
        arguments.toArray(String[]::new)));
    List<Path> jars = new ArrayList<>();
    for (var names : List.of(List.of("oracle.jdbc.OracleDriver", "oracle.jdbc.BrokenDriver",
        "oracle.sql.CharacterSet"),
        List.of("oracle.i18n.text.converter.CharacterConverterOGS"), List.of("oracle.xdb.XMLType"),
        List.of("oracle.xml.parser.v2.DOMParser"))) {
      Path jar = root.resolve("part-" + jars.size() + ".jar");
      try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
        for (String name : names) {
          String file = name.replace('.', '/') + ".class";
          output.putNextEntry(new JarEntry(file));
          output.write(Files.readAllBytes(root.resolve(file)));
          output.closeEntry();
        }
      }
      jars.add(jar);
    }
    return jars;
  }
}
