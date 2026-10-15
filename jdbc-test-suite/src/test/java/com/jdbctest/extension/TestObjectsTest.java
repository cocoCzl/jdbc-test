package com.jdbctest.extension;

import static org.junit.jupiter.api.Assertions.*;

import com.jdbctest.config.Config;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestObjectsTest {

  @TempDir
  Path directory;

  Config config(String prefix) {
    Config value = new Config();
    value.db = new Config.DbConfig();
    value.db.dialect = "postgresql";
    value.namespace = new Config.NamespaceConfig();
    value.namespace.name = "qa_scope";
    value.namespace.selection = "schema";
    value.namespace.destructiveConsent = true;
    value.namespace.objectPrefix = prefix;
    value.namespace.ledgerPath = directory.resolve(prefix + ".yaml").toString();
    return value;
  }

  Statement statement(List<String> commands, String failOn) {
    return (Statement) Proxy.newProxyInstance(getClass().getClassLoader(),
        new Class<?>[] {Statement.class}, (p, m, a) -> {
          if (m.getName().equals("executeUpdate") || m.getName().equals("execute")) {
            String sql = (String) a[0];
            commands.add(sql);
            if (failOn != null && sql.contains(failOn)) {
              throw new SQLException("fixture collision", "42P07");
            }
            if (m.getName().equals("execute")) {
              return false;
            }
            return 0;
          }
          return null;
        });
  }

  Connection connection(Statement statement) {
    return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
        new Class<?>[] {Connection.class},
        (p, m, a) -> m.getName().equals("createStatement") ? statement : null);
  }

  @Test
  void cleanupFailureAndUnknownCreateRemainVisibleAndAreNeverRetried() throws Exception {
    TestObjects objects = new TestObjects(config("j01234567890123456789"));
    var commands = new ArrayList<String>();
    objects.create(statement(commands, null), "CREATE TABLE {{owned}} (id INTEGER)");
    objects.cleanup(connection(statement(commands, "DROP")));
    var evidence = objects.cleanupEvidence();
    assertEquals("FAILED", evidence.get("status"));
    assertEquals(1L, evidence.get("attempted"));
    assertEquals(0L, evidence.get("confirmedDeleted"));
    objects.cleanup(connection(statement(commands, null)));
    assertEquals(2, commands.size());
    assertEquals(evidence, objects.cleanupEvidence());
    TestObjects uncertain = new TestObjects(config("j11234567890123456789"));
    assertThrows(SQLException.class, () -> uncertain.create(statement(commands, "CREATE"),
        "CREATE TABLE {{uncertain}} (id INTEGER)"));
    uncertain.cleanup(connection(statement(commands, null)));
    assertEquals("UNKNOWN", uncertain.cleanupEvidence().get("status"));
  }

  @Test
  void cleanupConnectionFailureCarriesSafeDiagnosticEvidence() {
    TestObjects objects = new TestObjects(config("j01234567890123456789"));
    objects.cleanupUnavailable(new SQLException("not public", "42501", 77));
    assertEquals("FAILED", objects.cleanupEvidence().get("status"));
    assertEquals(Map.of("phase", "CONNECTION", "sqlState", "42501", "vendorCode", 77),
        objects.cleanupEvidence().get("error"));
  }

  @Test
  void successfulCleanupAndRetainedNamespaceAreIdempotent() throws Exception {
    TestObjects objects = new TestObjects(config("j01234567890123456789"));
    objects.recordNamespaceProvisioning("CREATED_RETAINED");
    var commands = new ArrayList<String>();
    objects.create(statement(commands, null), "CREATE TABLE {{owned}} (id INTEGER)");
    objects.cleanup(connection(statement(commands, null)));
    objects.cleanup(connection(statement(commands, null)));
    assertEquals("SUCCESS", objects.cleanupEvidence().get("status"));
    assertEquals(1L, objects.cleanupEvidence().get("confirmedDeleted"));
    assertEquals(2, commands.size());
  }

  @Test
  void sqlServerIndexCleanupNamesItsOwnedTable() throws Exception {
    Config value = config("j01234567890123456789");
    value.db.dialect = "sqlserver";
    TestObjects objects = new TestObjects(value);
    List<String> commands = new ArrayList<>();
    Statement sql = statement(commands, null);
    objects.create(sql, "CREATE TABLE {{parent}} (id INTEGER)");
    objects.create(sql, "CREATE INDEX {{local:owned_index}} ON {{parent}} (id)");
    objects.cleanup(connection(sql));
    assertEquals(objects.sql("DROP INDEX {{local:owned_index}} ON {{parent}}"), commands.get(2));
    assertEquals("DROP TABLE " + objects.qualified("parent"), commands.get(3));
  }

  @Test
  void ownedViewIsDroppedBeforeItsParentAndFailedViewIsRetained() throws Exception {
    TestObjects objects = new TestObjects(config("j01234567890123456789"));
    List<String> commands = new ArrayList<>();
    Statement sql = statement(commands, objects.name("uncertain_view"));
    objects.create(sql, "CREATE TABLE {{parent}} (id INTEGER)");
    objects.create(sql, "CREATE VIEW {{owned_view}} AS SELECT id FROM {{parent}}");
    assertThrows(SQLException.class, () -> objects.create(sql,
        "CREATE VIEW {{uncertain_view}} AS SELECT id FROM {{parent}}"));
    objects.cleanup(connection(sql));
    assertEquals("DROP VIEW " + objects.qualified("owned_view"), commands.get(3));
    assertEquals("DROP TABLE " + objects.qualified("parent"), commands.get(4));
    assertEquals("UNKNOWN", objects.snapshot().get(2).get("state"));
    assertEquals(5, commands.size());
  }

  @Test
  void rejectsMissingScopeBeforeAnyDdl() {
    Config value = config("j01234567890123456789");
    value.namespace.name = null;
    assertThrows(IllegalArgumentException.class, () -> new TestObjects(value));
  }

  @Test
  void commentedFunctionUsesOwnershipJournalAndExactCleanup() throws Exception {
    TestObjects objects = new TestObjects(config("j01234567890123456789"));
    List<String> commands = new ArrayList<>();
    Statement sql = statement(commands, null);
    String body = "CREATE FUNCTION {{add_numbers}}(a INTEGER,b INTEGER) RETURNS INTEGER AS $$ BEGIN RETURN a+b; END; $$ LANGUAGE plpgsql";
    JdbcTestExtension.executeManagedStatement(sql, objects,
        "-- fixture function\n/* header /* nested */ comment */\n" + body);
    assertEquals("CREATED", objects.snapshot().get(0).get("state"));
    assertEquals(objects.sql(body), commands.get(0));
    objects.cleanup(connection(sql));
    assertEquals("DROP FUNCTION " + objects.qualified("add_numbers") + "(INTEGER, INTEGER)",
        commands.get(1));
    assertEquals("DROPPED", objects.snapshot().get(0).get("state"));
    assertThrows(IllegalStateException.class,
        () -> JdbcTestExtension.executeManagedStatement(sql, objects,
            "-- hidden drop\nDROP TABLE {{foreign_table}}"));
    assertThrows(IllegalArgumentException.class,
        () -> JdbcTestExtension.executeManagedStatement(sql, objects,
            "/*! CREATE TABLE hidden(id int) */"));
    JdbcTestExtension.executeManagedStatement(sql, objects, "-- comment only");
    assertEquals(2, commands.size());
  }

  @Test
  void runNamesAreDistinctShortAndExplicitlyQualified() {
    TestObjects first = new TestObjects(config("j01234567890123456789"));
    TestObjects second = new TestObjects(config("j11234567890123456789"));
    assertNotEquals(first.name("statement_test"), second.name("statement_test"));
    assertTrue(first.name("statement_test").length() <= 30);
    assertEquals(
        "SELECT 'statement_test' /* statement_test */ FROM " + first.qualified("statement_test"),
        first.sql("SELECT 'statement_test' /* statement_test */ FROM {{statement_test}}"));
  }

  @Test
  void failedCreateIsNeverDroppedAndEarlierOwnedObjectIsCleaned() throws Exception {
    TestObjects objects = new TestObjects(config("j01234567890123456789"));
    List<String> commands = new ArrayList<>();
    Statement sql = statement(commands, objects.name("second"));
    objects.create(sql, "CREATE TABLE {{first}} (id INTEGER)");
    assertThrows(SQLException.class,
        () -> objects.create(sql, "CREATE TABLE {{second}} (id INTEGER)"));
    objects.cleanup(connection(sql));
    assertEquals(List.of("CREATED", "UNKNOWN"),
        List.of("CREATED", objects.snapshot().get(1).get("state")));
    assertEquals("DROPPED", objects.snapshot().get(0).get("state"));
    assertEquals(3, commands.size());
    assertEquals("DROP TABLE " + objects.qualified("first"), commands.get(2));
    assertTrue(java.nio.file.Files.readString(directory.resolve("j01234567890123456789.yaml"))
        .contains("UNKNOWN"));
  }

  @Test
  void rejectsDuplicateCreateWithoutExecutingOrOverwriting() throws Exception {
    TestObjects objects = new TestObjects(config("j01234567890123456789"));
    List<String> commands = new ArrayList<>();
    Statement sql = statement(commands, null);
    objects.create(sql, "CREATE TABLE {{first}} (id INTEGER)");
    assertThrows(SQLException.class,
        () -> objects.create(sql, "CREATE TABLE {{first}} (id INTEGER)"));
    assertEquals(1, commands.size());
    objects.drop(connection(sql), "first");
    objects.create(sql, "CREATE TABLE {{first}} (id INTEGER)");
    assertEquals(3, commands.size());
  }
}
