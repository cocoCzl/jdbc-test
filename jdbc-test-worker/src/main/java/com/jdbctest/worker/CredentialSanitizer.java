package com.jdbctest.worker;

final class CredentialSanitizer {

  private static volatile String knownPassword;

  private CredentialSanitizer() {
  }

  static void password(String value) {
    knownPassword = value;
  }

  static String sanitize(String value) {
    String raw = value == null ? "" : value;
    String password = knownPassword;
    if (password != null && !password.isEmpty()) {
      // Match the complete raw secret first, but do not replace substrings of an existing mask.
      raw = java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(password)
          + "|" + java.util.regex.Pattern.quote("[REDACTED]"))
          .matcher(raw).replaceAll(java.util.regex.Matcher.quoteReplacement("[REDACTED]"));
    }
    return com.jdbctest.security.CredentialRedactor.sanitize(raw);
  }

  static Object project(Object value) {
    if (value instanceof java.util.Map<?, ?> fields) {
      var result = new java.util.LinkedHashMap<String, Object>();
      fields.forEach((key, item) -> result.put(String.valueOf(key),
          com.jdbctest.security.CredentialRedactor.sensitiveKey(String.valueOf(key)) && item != null
              ? "[REDACTED]"
              : item instanceof String text && java.util.Set.of("message", "stackTrace",
              "databaseProductName", "databaseProductVersion", "driverName", "driverVersion")
              .contains(String.valueOf(key)) ? sanitize(text) : project(item)));
      return result;
    }
    if (value instanceof java.util.List<?> values) {
      return values.stream().map(CredentialSanitizer::project).toList();
    }
    return com.jdbctest.security.CredentialRedactor.sanitizeValue(value);
  }
}
