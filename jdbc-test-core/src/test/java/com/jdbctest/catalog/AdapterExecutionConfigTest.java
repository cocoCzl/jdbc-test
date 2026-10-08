package com.jdbctest.catalog;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class AdapterExecutionConfigTest {

  @Test
  void packagedOfficialManifestsCarryCompleteBooleanDeclarations() {
    for (String id : List.of("mysql", "postgresql", "oracle", "sqlserver", "gaussdb")) {
      var config = AdapterExecutionConfig.load(id);
      assertTrue(
          config.capabilities().keySet().containsAll(AdapterExecutionConfig.REQUIRED_CAPABILITIES),
          id);
      assertNotNull(config.identifierQuote());
    }
    assertTrue(AdapterExecutionConfig.load("postgresql").capabilities().get("array_type"));
    assertFalse(AdapterExecutionConfig.load("mysql").capabilities().get("array_type"));
  }

  @Test
  void missingAndInvalidDeclarationsAreConfigurationErrorsNotDisabledCapabilities() {
    assertThrows(IllegalArgumentException.class,
        () -> AdapterExecutionConfig.validateCapabilities(Map.of()));
    Map<String, Object> invalid = new HashMap<>(
        AdapterExecutionConfig.load("postgresql").capabilities());
    invalid.put("array_type", "false");
    assertThrows(IllegalArgumentException.class,
        () -> AdapterExecutionConfig.validateCapabilities(invalid));
    invalid.remove("array_type");
    assertThrows(IllegalArgumentException.class,
        () -> AdapterExecutionConfig.validateCapabilities(invalid));
  }

  @Test
  void disablingIsPreservedAndSnapshotsAreImmutable() {
    var values = new HashMap<>(AdapterExecutionConfig.load("postgresql").capabilities());
    values.put("array_type", false);
    var snapshot = AdapterExecutionConfig.validateCapabilities(values);
    values.put("array_type", true);
    assertFalse(snapshot.get("array_type"));
    assertThrows(UnsupportedOperationException.class, () -> snapshot.put("array_type", true));
  }
}
