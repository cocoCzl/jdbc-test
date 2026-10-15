package com.jdbctest.worker;

final class CredentialSanitizer {

  private CredentialSanitizer() {
  }

  static String sanitize(String value) {
    return value == null ? "" : com.jdbctest.security.CredentialRedactor.sanitize(value);
  }
}
