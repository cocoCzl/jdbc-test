package com.jdbctest.extension;

import static org.junit.jupiter.api.Assertions.*;

import com.jdbctest.config.Config;
import java.nio.file.Path;
import java.sql.*;
import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;

@Tag("integration")
class ObjectIsolationIT {

  @TempDir
  Path directory;

  @Test
  void postgresqlScopesEveryCheckoutAndPreservesProtectionObjects() throws Exception {
    try (var database = new PostgreSQLContainer<>("postgres:16").withPassword(
        "temporary-fixture-password")) {
      database.start();
      try (var admin = DriverManager.getConnection(database.getJdbcUrl(), database.getUsername(),
          database.getPassword())) {
        try (var sql = admin.createStatement()) {
          sql.execute("CREATE SCHEMA qa_scope");
        }
        verify("postgresql", "org.postgresql.Driver", database.getJdbcUrl(), database.getUsername(),
            database.getPassword(), database.getDatabaseName(), admin);
      }
    }
  }

  @Test
  void mysqlScopesEveryCheckoutAndPreservesProtectionObjects() throws Exception {
    try (var database = new GenericContainer<>("mysql:8.4").withEnv("MYSQL_ROOT_PASSWORD",
            "temporary-fixture-password")
        .withEnv("MYSQL_DATABASE", "qa_scope").withExposedPorts(3306)
        .waitingFor(Wait.forLogMessage(".*ready for connections.*port: 3306.*\\n", 1))
        .withStartupTimeout(Duration.ofMinutes(2))) {
      database.start();
      String url = "jdbc:mysql://" + database.getHost() + ":" + database.getMappedPort(3306)
          + "/qa_scope?allowPublicKeyRetrieval=true&sslMode=DISABLED";
      // Authentication settings are limited to this newly created disposable fixture server.
      try (var admin = DriverManager.getConnection(url, "root", "temporary-fixture-password")) {
        verify("mysql", "com.mysql.cj.jdbc.Driver", url, "root", "temporary-fixture-password",
            "qa_scope", admin);
      }
    }
  }

  @Test
  void hardStopRetainsConfirmedObjectInventoryWithoutPretendingFinallyRan() throws Exception {
    try (var database = new PostgreSQLContainer<>("postgres:16").withPassword(
        "temporary-hard-stop-fixture")) {
      database.start();
      try (var admin = DriverManager.getConnection(database.getJdbcUrl(), database.getUsername(),
          database.getPassword()); var sql = admin.createStatement()) {
        sql.execute("CREATE SCHEMA qa_hard_stop");
        Config config = new Config();
        config.db = new Config.DbConfig();
        config.db.dialect = "postgresql";
        config.db.url = database.getJdbcUrl();
        config.db.username = database.getUsername();
        config.db.password = database.getPassword();
        config.namespace = new Config.NamespaceConfig();
        config.namespace.name = "qa_hard_stop";
        config.namespace.selection = "schema";
        config.namespace.objectPrefix = "j91234567890123456789";
        config.namespace.destructiveConsent = true;
        config.namespace.ledgerPath = directory.resolve("hard-stop.yaml").toString();
        var child = new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
            System.getProperty("java.class.path"), HardStopFixture.class.getName())
            .redirectError(directory.resolve("hard-stop.log").toFile()).start();
        try {
          child.getOutputStream().write(
              new com.fasterxml.jackson.databind.ObjectMapper().setVisibility(
                      com.fasterxml.jackson.annotation.PropertyAccessor.GETTER,
                      com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
                  .setVisibility(com.fasterxml.jackson.annotation.PropertyAccessor.IS_GETTER,
                      com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
                  .writeValueAsBytes(config));
          child.getOutputStream().close();
          assertEquals("READY", ProcessReadiness.awaitLine(child, Duration.ofSeconds(15)), () -> {
            try {
              return java.nio.file.Files.readString(directory.resolve("hard-stop.log"));
            } catch (java.io.IOException error) {
              return error.toString();
            }
          });
          child.destroyForcibly();
          assertTrue(child.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
        } finally {
          if (child.isAlive()) {
            child.destroyForcibly();
          }
        }
        var ledger = new org.yaml.snakeyaml.Yaml().loadAs(
            java.nio.file.Files.readString(Path.of(config.namespace.ledgerPath)),
            java.util.Map.class);
        var entries = (java.util.List<java.util.Map<String, Object>>) ledger.get("objects");
        assertEquals("CREATED", entries.getFirst().get("state"));
        try (var rows = sql.executeQuery(
            "SELECT COUNT(*) FROM " + new TestObjects(config).qualified("interrupted"))) {
          assertTrue(rows.next());
        }
        Path evidence = Path.of("target", "acceptance-evidence");
        java.nio.file.Files.createDirectories(evidence);
        java.nio.file.Files.copy(Path.of(config.namespace.ledgerPath),
            evidence.resolve("hard-stop-object-ledger.yaml"),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        // No guessed SQL recovery. Container teardown owns this entire disposable fixture environment.
      }
    }
  }

  public static class HardStopFixture {

    public static void main(String[] args) throws Exception {
      Config config = new com.fasterxml.jackson.databind.ObjectMapper().readValue(System.in,
          Config.class);
      var objects = new TestObjects(config);
      try (var connection = DriverManager.getConnection(config.db.url, config.db.username,
          config.db.password); var sql = connection.createStatement()) {
        objects.create(sql, "CREATE TABLE {{interrupted}} (id INTEGER)");
        System.out.println("READY");
        System.out.flush();
        new java.util.concurrent.CountDownLatch(1).await();
      }
    }
  }

  void verify(String adapter, String driver, String url, String user, String password,
      String database, Connection admin) throws Exception {
    Config config = new Config();
    config.db = new Config.DbConfig();
    config.db.dialect = adapter;
    config.db.username = user;
    config.db.password = password;
    config.db.identifierQuote = adapter.equals("mysql") ? "`" : "\"";
    config.namespace = new Config.NamespaceConfig();
    config.namespace.selection = adapter.equals("mysql") ? "catalog" : "schema";
    config.namespace.name = "qa_scope";
    config.namespace.database = database;
    config.namespace.destructiveConsent = true;
    config.namespace.objectPrefix = "j01234567890123456789";
    config.namespace.ledgerPath = directory.resolve(adapter + ".yaml").toString();
    var poolConfig = new com.zaxxer.hikari.HikariConfig();
    poolConfig.setJdbcUrl(url);
    poolConfig.setUsername(user);
    poolConfig.setPassword(password);
    poolConfig.setMaximumPoolSize(1);
    poolConfig.setMinimumIdle(1);
    try (var pool = new ScopedDataSource(new com.zaxxer.hikari.HikariDataSource(poolConfig),
        config)) {
      try (var pooled = pool.getConnection()) {
        assertEquals("qa_scope",
            adapter.equals("mysql") ? pooled.getCatalog() : pooled.getSchema());
        // Exercise real session changes on this disposable fixture, then verify the next checkout resets scope.
        if (adapter.equals("mysql")) {
          pooled.setCatalog("information_schema");
        } else {
          pooled.setSchema("public");
        }
      }
      try (var pooled = pool.getConnection(user, password)) {
        assertEquals("qa_scope",
            adapter.equals("mysql") ? pooled.getCatalog() : pooled.getSchema());
      }
    }
    var first = new TestObjects(config);
    var datasource = new ScopedDataSource(
        new DriverManagerDataSource(driver, url, user, password, java.util.Map.of()), config);
    try (datasource; var connection = datasource.getConnection();
        var sql = connection.createStatement(); var protection = admin.createStatement()) {
      protection.execute("CREATE TABLE qa_scope.statement_test (id INTEGER)");
      protection.execute("INSERT INTO qa_scope.statement_test VALUES (719)");
      first.create(sql, "CREATE TABLE {{statement_test}} (id INTEGER)");
      sql.executeUpdate(first.sql("INSERT INTO {{statement_test}} VALUES (1)"));
      config.namespace.objectPrefix = "j11234567890123456789";
      config.namespace.ledgerPath = directory.resolve(adapter + "-second.yaml").toString();
      var second = new TestObjects(config);
      assertNotEquals(first.name("statement_test"), second.name("statement_test"));
      second.create(sql, "CREATE TABLE {{statement_test}} (id INTEGER)");
      second.cleanup(connection);
      try (var rows = sql.executeQuery(first.sql("SELECT id FROM {{statement_test}}"))) {
        assertTrue(rows.next());
        assertEquals(1, rows.getInt(1));
      }
      try (var reconnect = datasource.getConnection(user, password)) {
        assertEquals("qa_scope",
            adapter.equals("mysql") ? reconnect.getCatalog() : reconnect.getSchema());
        try (var check = reconnect.createStatement(); var rows = check.executeQuery(
            first.sql("SELECT id FROM {{statement_test}}"))) {
          assertTrue(rows.next());
          assertEquals(1, rows.getInt(1));
        }
      }
      // An externally created exact physical collision must survive failed CREATE and cleanup.
      protection.execute("CREATE TABLE " + first.qualified("collision") + " (id INTEGER)");
      assertThrows(SQLException.class,
          () -> first.create(sql, "CREATE TABLE {{collision}} (id INTEGER)"));
      first.cleanup(connection);
      try (var rows = protection.executeQuery("SELECT id FROM qa_scope.statement_test")) {
        assertTrue(rows.next());
        assertEquals(719, rows.getInt(1));
      }
      try (var rows = protection.executeQuery(
          "SELECT COUNT(*) FROM " + first.qualified("collision"))) {
        assertTrue(rows.next());
      }
      assertThrows(SQLException.class,
          () -> sql.executeQuery(first.sql("SELECT * FROM {{statement_test}}")));
      assertEquals("UNKNOWN", first.snapshot().get(1).get("state"));
      // Fixtures are removed by destroying only this test's container, never by broad SQL cleanup.
    }
  }
}
