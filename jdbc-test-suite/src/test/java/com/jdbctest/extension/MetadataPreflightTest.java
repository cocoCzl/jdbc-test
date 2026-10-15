package com.jdbctest.extension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.jdbctest.config.Config;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MetadataPreflightTest {

  @TempDir
  Path directory;

  @Test
  void declarationsAreCheckedBeforeAutoNamespaceOrFixtureWrites() throws Exception {
    for (String failure : new String[] {"product", "driver", "databaseLow", "databaseHigh",
        "driverLow", "driverHigh", "regex", "unparseable", "invalidBound"}) {
      Config config = config();
      DatabaseMetaData metadata = mock(DatabaseMetaData.class);
      when(metadata.getDatabaseProductName()).thenReturn("PostgreSQL");
      when(metadata.getDatabaseProductVersion()).thenReturn("PostgreSQL 16.2 vendor build");
      when(metadata.getDriverName()).thenReturn("PostgreSQL JDBC Driver");
      when(metadata.getDriverVersion()).thenReturn("42.7.2");
      switch (failure) {
        case "product" -> config.db.expectedDatabaseProductRegex = "Oracle";
        case "driver" -> config.db.expectedDriverNameRegex = "Other Driver";
        case "databaseLow" -> config.db.databaseVersionMin = "17";
        case "databaseHigh" -> config.db.databaseVersionMax = "15.9";
        case "driverLow" -> config.db.driverVersionMin = "43";
        case "driverHigh" -> config.db.driverVersionMax = "41";
        case "regex" -> config.db.expectedDatabaseProductRegex = "[";
        case "unparseable" -> when(metadata.getDatabaseProductVersion()).thenReturn("unknown");
        case "invalidBound" -> config.db.driverVersionMin = "not-a-version";
      }
      Connection connection = mock(Connection.class);
      when(connection.getMetaData()).thenReturn(metadata);
      Driver driver = mock(Driver.class);
      when(driver.connect(anyString(), any())).thenReturn(connection);
      JdbcContext.setIsolatedDriver(driver);
      try {
        assertEquals("ADAPTER_PREFLIGHT_FAILED",
            assertThrows(IllegalStateException.class, () -> JdbcContext.init(config)).getMessage());
        assertEquals("REJECTED", JdbcContext.metadataEvidence().get("status"));
        verify(connection, never()).createStatement();
        verify(connection, never()).prepareStatement(anyString());
      } finally {
        JdbcContext.shutdown();
      }
    }
  }

  @Test
  void endpointsPrefixesManySegmentsAndLargeNumbersAreHandled() throws Exception {
    var method = JdbcContext.class.getDeclaredMethod("validateVersion", String.class,
        String.class, String.class, String.class);
    method.setAccessible(true);
    for (String[] values : new String[][] {
        {"PostgreSQL 16.2 vendor", "16.2", "16.2"},
        {"1.2.3.4.5", "1.2.3.4.5", "1.2.3.4.6"},
        {"99999999999999999999.2", "99999999999999999999", null},
        {"unknown", null, null}}) {
      assertDoesNotThrow(() -> method.invoke(null, "fixture", values[0], values[1], values[2]));
    }
  }

  private Config config() {
    Config config = new Config();
    config.adapter = new Config.AdapterConfig();
    config.adapter.capabilities = com.jdbctest.catalog.AdapterExecutionConfig.load("postgresql")
        .capabilities();
    config.db = new Config.DbConfig();
    config.db.adapterId = "postgresql";
    config.db.dialect = "postgresql";
    config.db.url = "jdbc:fixture:preflight";
    config.db.connectionMode = "driver_manager";
    config.db.expectedDatabaseProductRegex = "PostgreSQL";
    config.db.expectedDriverNameRegex = "PostgreSQL JDBC Driver";
    config.db.databaseVersionMin = "9.6";
    config.db.databaseVersionMax = "99";
    config.db.driverVersionMin = "42";
    config.db.driverVersionMax = "43";
    config.namespace = new Config.NamespaceConfig();
    config.namespace.mode = "auto";
    config.namespace.selection = "schema";
    config.namespace.name = "owned_scope";
    config.namespace.objectPrefix = "j01234567890123456789";
    config.namespace.destructiveConsent = true;
    config.namespace.createSql = "CREATE SCHEMA owned_scope";
    config.namespace.ledgerPath = directory.resolve("objects.yaml").toString();
    return config;
  }
}
