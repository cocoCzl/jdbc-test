package com.jdbctest.catalog;

import java.util.List;
import java.util.Objects;

/**
 * Stable metadata for one reportable JDBC scenario.
 */
public record TestCaseDescriptor(
    String scenarioId,
    TestModule module,
    TestProfile profile,
    String contract,
    String sourceClass,
    String sourceMethod,
    List<String> requiredCapabilities
) {

  public TestCaseDescriptor {
    requireText(scenarioId, "scenarioId");
    Objects.requireNonNull(module, "module");
    Objects.requireNonNull(profile, "profile");
    requireText(contract, "contract");
    requireText(sourceClass, "sourceClass");
    requireText(sourceMethod, "sourceMethod");
    requiredCapabilities = List.copyOf(
        Objects.requireNonNull(requiredCapabilities, "requiredCapabilities"));
  }

  private static void requireText(String value, String field) {
    Objects.requireNonNull(value, field);
    if (value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
  }
}
