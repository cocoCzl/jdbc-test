package com.jdbctest.catalog;

/**
 * An explicitly authorized existing database/schema; never inferred from a login default.
 */
public record ExecutionScope(String database, String schema) {

  public ExecutionScope validate(String adapterId) {
    return validate(AdapterExecutionConfig.load(adapterId).sqlDialect());
  }

  public ExecutionScope validate(SqlDialect dialect) {
    if (dialect == SqlDialect.MYSQL) {
      identifier(database);
      if (schema != null && !schema.isBlank() && !schema.equals(database)) {
        throw new IllegalArgumentException("MySQL scope uses database, not a different schema");
      }
    } else {
      identifier(schema);
      if (dialect.requiresDatabase()) {
        identifier(database);
      }
    }
    return this;
  }

  public String namespace(String adapterId) {
    return namespace(AdapterExecutionConfig.load(adapterId).sqlDialect());
  }

  public String namespace(SqlDialect dialect) {
    validate(dialect);
    return dialect.normalizeIdentifier(dialect == SqlDialect.MYSQL ? database : schema);
  }

  private static void identifier(String value) {
    if (value == null || !value.matches("[A-Za-z][A-Za-z0-9_]{0,62}")) {
      throw new IllegalArgumentException("An explicit database/schema identifier is required");
    }
  }
}
