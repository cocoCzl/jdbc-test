package com.jdbctest.server.security;

import java.util.regex.Pattern;

public final class JdbcUrlSanitizer {
    private static final Pattern QUERY_SECRET = Pattern.compile(
            "(?i)((?:password|passwd|pwd|secret|token|access_token)\\s*=\\s*)[^&;,#\\s]*");
    private static final Pattern COLON_SECRET = Pattern.compile(
            "(?i)((?:password|passwd|pwd|secret|token|access_token)\\s*[:=]\\s*)[^,;}\\s]*");

    private JdbcUrlSanitizer() {
    }

    public static String sanitize(String value) {
        if (value == null || value.isEmpty()) return value;
        return COLON_SECRET.matcher(QUERY_SECRET.matcher(value).replaceAll("$1[REDACTED]")
                ).replaceAll("$1[REDACTED]");
    }

    public static boolean containsCredentials(String value) {
        return value != null && (QUERY_SECRET.matcher(value).find() || COLON_SECRET.matcher(value).find());
    }

    public static String sanitizeJson(String source) {
        var mapper = new tools.jackson.databind.ObjectMapper();
        return mapper.writeValueAsString(sanitizeValue(mapper.readValue(source, Object.class)));
    }

    private static Object sanitizeValue(Object value) {
        if (value instanceof String text) return sanitize(text);
        if (value instanceof java.util.List<?> values) return values.stream().map(JdbcUrlSanitizer::sanitizeValue).toList();
        if (value instanceof java.util.Map<?, ?> fields) {
            var safe = new java.util.LinkedHashMap<String, Object>();
            fields.forEach((key, item) -> safe.put(String.valueOf(key), sanitizeValue(item)));
            return safe;
        }
        return value;
    }
}
