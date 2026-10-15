package com.jdbctest.server.connection;

import com.jdbctest.catalog.AdapterDefinition;
import java.util.List;
import java.util.Map;

/**
 * Public declaration hints only; never includes local driver paths or evidence file contents.
 */
public record AdapterResponse(String id, String name, String status, String dialect,
    String namespaceSelection, boolean requiresDatabase, String driverClass,
    String recommendedMavenCoordinate, List<ValidatedCombination> validatedCombinations) {

  static AdapterResponse from(AdapterDefinition definition) {
    var metadata = definition.metadata();
    Map<?, ?> driver = (Map<?, ?>) definition.declaration().get("driver");
    Map<?, ?> source = (Map<?, ?>) driver.get("source");
    List<?> combinations = (List<?>) definition.declaration().get("validated_combinations");
    return new AdapterResponse(metadata.id(), metadata.name(), metadata.status(),
        definition.dialect().code(), definition.dialect().selection(),
        definition.dialect().requiresDatabase(), (String) driver.get("class"),
        "maven".equals(source.get("kind")) ? (String) source.get("coordinate") : null,
        combinations.stream().map(item -> {
          Map<?, ?> combination = (Map<?, ?>) item;
          return new ValidatedCombination((String) combination.get("database_version"),
              (String) combination.get("driver_version"));
        }).toList());
  }

  record ValidatedCombination(String databaseVersion, String driverVersion) {
  }
}
