package com.jdbctest.catalog;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

class DataSourceProvidersTest {
  @Test
  void roundTripsDefinitionsAndDefaultsWithoutInferringVendors() {
    var definition = new DataSourceProviders.Provider("custom.jdbc.Source", null, null, null);
    var providers = new DataSourceProviders(definition, null, definition);
    assertEquals(providers, DataSourceProviders.fromMap(providers.toMap()));
    assertEquals("URL", definition.urlProperty());
    var features = providers.capabilities(AdapterExecutionConfig.load("postgresql").capabilities());
    assertTrue(features.get("data_source"));
    assertTrue(features.get("xa_data_source"));
    assertFalse(features.get("connection_pool"));
    assertEquals(DataSourceProviders.empty(), DataSourceProviders.fromMap(null));
  }

  @Test
  void rejectsMalformedDefinitionsAndUnknownFields() {
    assertThrows(IllegalArgumentException.class,
        () -> new DataSourceProviders.Provider("not a class", null, null, null));
    assertThrows(IllegalArgumentException.class,
        () -> new DataSourceProviders.Provider("custom.Source", "URL", "user", "User"));
    assertThrows(IllegalArgumentException.class,
        () -> DataSourceProviders.fromMap(Map.of("dataSource", Map.of("password", "secret"))));
    assertThrows(IllegalArgumentException.class,
        () -> DataSourceProviders.fromMap(Map.of("unknown", Map.of())));
  }
}
