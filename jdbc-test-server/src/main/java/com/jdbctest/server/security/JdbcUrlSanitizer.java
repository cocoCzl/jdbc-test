package com.jdbctest.server.security;

import com.jdbctest.security.CredentialRedactor;

public final class JdbcUrlSanitizer {

  private JdbcUrlSanitizer() {
  }

  public static String sanitize(String value) {
    return CredentialRedactor.sanitize(value);
  }

  public static boolean containsCredentials(String value) {
    return CredentialRedactor.containsCredentials(value);
  }

  public static String sanitizeJson(String source) {
    var mapper = new tools.jackson.databind.ObjectMapper();
    return mapper.writeValueAsString(CredentialRedactor.sanitizeValue(
        mapper.readValue(source, Object.class)));
  }
}
