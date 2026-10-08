package com.jdbctest.catalog;

/**
 * An explicitly authorized existing database/schema; never inferred from a login default.
 */
public record ExecutionScope(String database, String schema) {

  public ExecutionScope validate(String adapterId) {
    if ("mysql".equals(adapterId)) {
      identifier(database);
      if (schema != null && !schema.isBlank() && !schema.equals(database)) {
        throw new IllegalArgumentException("MySQL scope uses database, not a different schema");
      }
    } else {
      identifier(schema);
      if (!"oracle".equals(adapterId)) {
        identifier(database);
      }
    }
    return this;
  }

  public String namespace(String adapterId) {
    validate(adapterId);
    return "mysql".equals(adapterId) ? database : "oracle".equals(adapterId)
        ? schema.toUpperCase(java.util.Locale.ROOT) : schema;
  }

  private static void identifier(String value) {
    if (value == null || !value.matches("[A-Za-z][A-Za-z0-9_]{0,62}")) {
      throw new IllegalArgumentException("An explicit database/schema identifier is required");
    }
  }
}
