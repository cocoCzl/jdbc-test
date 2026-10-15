package com.jdbctest.worker;

final class CredentialSanitizer {

  private static volatile String knownPassword;

  private CredentialSanitizer() {
  }

  static void password(String value) {
    knownPassword = value;
  }

  static String sanitize(String value) {
    String safe = value == null ? "" : com.jdbctest.security.CredentialRedactor.sanitize(value);
    String password = knownPassword;
    return password == null || password.isEmpty() ? safe : safe.replace(password, "[REDACTED]");
  }

  static Object project(Object value) {
    Object safe = com.jdbctest.security.CredentialRedactor.sanitizeValue(value);
    if (safe instanceof java.util.Map<?, ?> fields) {
      var result = new java.util.LinkedHashMap<String, Object>();
      fields.forEach((key, item) -> result.put(String.valueOf(key),
          item instanceof String text && java.util.Set.of("message", "stackTrace",
              "databaseProductName", "databaseProductVersion", "driverName", "driverVersion")
              .contains(String.valueOf(key)) ? sanitize(text) : project(item)));
      return result;
    }
    if (safe instanceof java.util.List<?> values) {
      return values.stream().map(CredentialSanitizer::project).toList();
    }
    return safe;
  }
}
