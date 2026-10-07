package com.jdbctest.server.security;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JdbcUrlSanitizerTest {
    @Test
    void redactsCommonJdbcSecrets() {
        assertEquals("jdbc:postgresql://host/db", JdbcUrlSanitizer.sanitize("jdbc:postgresql://host/db"));
        String value = JdbcUrlSanitizer.sanitize("jdbc:postgresql://host/db?user=test&password=fake-secret&ssl=true");
        assertFalse(value.contains("fake-secret"));
        assertTrue(value.contains("password=[REDACTED]"));
        assertFalse(JdbcUrlSanitizer.sanitize("jdbc:x?PASSWORD=fake-secret&token=abc").contains("fake-secret"));
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
        assertFalse(safe.contains("fake-secret")); assertFalse(safe.contains("fake-token"));
        assertEquals(1, report.path("summary").path("total").asInt());
        assertEquals(42, report.path("rows").get(0).path("durationMs").asInt());
        assertTrue(report.path("rows").get(0).path("optional").isNull());
        assertEquals("jdbc:postgresql://host/db?password=[REDACTED]", report.path("rows").get(0).path("stackTrace").asString());
        assertEquals(safe, JdbcUrlSanitizer.sanitizeJson(safe));
    }
}
