package com.jdbctest.server.security;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class JdbcUrlSanitizerTest {

  @Test
  void documentedTlsSecretsAreDetectedAndProjectedWithoutBroadPasswordMatching() {
    for (String key : new String[] {"sslpassword", "trustStorePassword", "clientKeyPassword",
        "keyStoreSecret", "clientCertificateKeyStorePassword", "trustCertificateKeyStorePassword",
        "javax.net.ssl.keyStorePassword", "javax.net.ssl.trustStorePassword"}) {
      String url = "jdbc:fixture://host/db?" + key + "=ReviewOnlyTlsHeadReviewOnlyTlsTail&encrypt=true";
      assertTrue(JdbcUrlSanitizer.containsCredentials(url), key);
      assertTrue(JdbcUrlSanitizer.containsCredentials(url.toUpperCase(java.util.Locale.ROOT)), key);
      String safe = JdbcUrlSanitizer.sanitize(url);
      assertFalse(safe.contains("ReviewOnlyTlsHead"), key);
      assertFalse(safe.contains("ReviewOnlyTlsTail"), key);
      assertTrue(safe.endsWith("&encrypt=true"));
      var mapper = new tools.jackson.databind.ObjectMapper();
      String nested = mapper.writeValueAsString(java.util.Map.of("nested", java.util.List.of(
          java.util.Map.of(key.toUpperCase(java.util.Locale.ROOT), "ReviewOnlyTlsHeadReviewOnlyTlsTail"))));
      String projected = JdbcUrlSanitizer.sanitizeJson(nested);
      assertFalse(projected.contains("ReviewOnlyTls"), key);
      assertEquals(projected, JdbcUrlSanitizer.sanitizeJson(projected));
    }
    String ordinary = "jdbc:fixture://host/db?passwordRequired=true&sslpasswordcallback=fixture.Callback";
    assertFalse(JdbcUrlSanitizer.containsCredentials(ordinary));
    assertEquals(ordinary, JdbcUrlSanitizer.sanitize(ordinary));
    String fields = "{\"passwordRequired\":true,\"sslpasswordcallback\":\"fixture.Callback\",\"tokenCount\":2}";
    assertEquals(new tools.jackson.databind.ObjectMapper().readTree(fields),
        new tools.jackson.databind.ObjectMapper().readTree(JdbcUrlSanitizer.sanitizeJson(fields)));
  }

  @Test
  void dialectCredentialsAndStructuredKeysAreSafe() {
    for (String input : new String[] {
        "jdbc:oracle:thin:audit_user/AuditOnly_Secret@db.example:1521:TEST",
        "jdbc:oracle:oci:audit_user/AuditOnly_Secret@TEST",
        "jdbc:oracle:thin:u/AuditOnly@SecretTail@host",
        "jdbc:sqlserver://host;password={AuditOnly;SecretTail}}End};encrypt=true",
        "password=\"AuditOnly Alpha Beta\"",
        "password={AuditOnly;SecretTail",
        "jdbc:x?%70assword=AuditOnly%20SecretTail"}) {
      assertTrue(JdbcUrlSanitizer.containsCredentials(input));
      String safe = JdbcUrlSanitizer.sanitize(input);
      assertFalse(safe.contains("AuditOnly"));
      assertFalse(safe.contains("SecretTail"));
      assertEquals(safe, JdbcUrlSanitizer.sanitize(safe));
    }
    for (String input : new String[] {"jdbc:oracle:thin:@host:1521:SID",
        "jdbc:oracle:thin:@//host/service", "jdbc:oracle:thin:@TNS_ALIAS",
        "jdbc:oracle:thin:@(DESCRIPTION=(ADDRESS=(HOST=host)))"}) {
      assertFalse(JdbcUrlSanitizer.containsCredentials(input));
      assertEquals(input, JdbcUrlSanitizer.sanitize(input));
    }
    String safe = JdbcUrlSanitizer.sanitizeJson("""
        {"PASSWORD":"ExampleSecret","nested":[{"access_token":"TokenSecret"}],
         "passwordRequired":true,"tokenCount":2,"optional":null}
        """);
    assertFalse(safe.contains("ExampleSecret"));
    assertFalse(safe.contains("TokenSecret"));
    var json = new tools.jackson.databind.ObjectMapper().readTree(safe);
    assertTrue(json.path("passwordRequired").asBoolean());
    assertEquals(2, json.path("tokenCount").asInt());
  }

  @Test
  void redactsCommonJdbcSecrets() {
    assertEquals("jdbc:postgresql://host/db",
        JdbcUrlSanitizer.sanitize("jdbc:postgresql://host/db"));
    String value = JdbcUrlSanitizer.sanitize(
        "jdbc:postgresql://host/db?user=test&password=fake-secret&ssl=true");
    assertFalse(value.contains("fake-secret"));
    assertTrue(value.contains("password=[REDACTED]"));
    assertFalse(
        JdbcUrlSanitizer.sanitize("jdbc:x?PASSWORD=fake-secret&token=abc").contains("fake-secret"));
  }

  @Test
  void doesNotModifyUnrelatedParameters() {
    String value = JdbcUrlSanitizer.sanitize("jdbc:x?ssl=true&applicationName=test");
    assertEquals("jdbc:x?ssl=true&applicationName=test", value);
  }

  @Test
  void reportSanitizationPreservesJsonStructureAndScalarTypes() {
    String source = "{\"summary\":{\"total\":1},\"rows\":[{\"message\":\"password=fake-secret token=fake-token\",\"stackTrace\":\"jdbc:postgresql://host/db?password=fake-secret\",\"durationMs\":42,\"optional\":null}]}";
    String safe = JdbcUrlSanitizer.sanitizeJson(source);
    var report = new tools.jackson.databind.ObjectMapper().readTree(safe);
    assertFalse(safe.contains("fake-secret"));
    assertFalse(safe.contains("fake-token"));
    assertEquals(1, report.path("summary").path("total").asInt());
    assertEquals(42, report.path("rows").get(0).path("durationMs").asInt());
    assertTrue(report.path("rows").get(0).path("optional").isNull());
    assertEquals("jdbc:postgresql://host/db?password=[REDACTED]",
        report.path("rows").get(0).path("stackTrace").asString());
    assertEquals(safe, JdbcUrlSanitizer.sanitizeJson(safe));
  }
}
