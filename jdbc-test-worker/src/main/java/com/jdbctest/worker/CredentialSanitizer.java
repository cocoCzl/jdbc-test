package com.jdbctest.worker;

import java.util.regex.Pattern;

final class CredentialSanitizer {

  private static final Pattern KEY_VALUE = Pattern.compile(
      "(?i)(password|passwd|pwd|secret|token)(\\s*[=:]\\s*)[^,;\\s}]+");

  private CredentialSanitizer() {
  }

  static String sanitize(String value) {
    return value == null ? "" : KEY_VALUE.matcher(value).replaceAll("$1$2[REDACTED]");
  }
}
