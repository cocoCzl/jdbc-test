package com.jdbctest.security;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Safe public projections. Never use the result as driver input. */
public final class CredentialRedactor {

  private static final String MASK = "[REDACTED]";
  private static final Set<String> KEYS = Set.of("password", "passwd", "pwd", "secret", "token",
      "access_token", "accesstoken", "client_secret", "clientsecret", "api_key", "apikey",
      "encryptedpassword", "masterkey", "master_key");
  private static final Pattern ASSIGNMENT = Pattern.compile(
      "(?i)(?<![\\w])((?:[a-z_]|%[0-9a-f]{2})+)[\\\"']?\\s*[:=]\\s*");
  private static final Pattern ORACLE = Pattern.compile(
      "(?i)(jdbc:oracle:(?:thin|oci|oci8):)(?!@)([^\\r\\n]*)");

  private CredentialRedactor() {
  }

  public static boolean sensitiveKey(String key) {
    String decoded = key.replaceAll("(?i)%5f", "_");
    var encoded = Pattern.compile("%([0-9a-fA-F]{2})").matcher(decoded);
    var buffer = new StringBuilder();
    while (encoded.find()) {
      encoded.appendReplacement(buffer, java.util.regex.Matcher.quoteReplacement(
          Character.toString((char) Integer.parseInt(encoded.group(1), 16))));
    }
    encoded.appendTail(buffer);
    return KEYS.contains(buffer.toString().toLowerCase(Locale.ROOT));
  }

  public static boolean containsCredentials(String value) {
    if (value == null) {
      return false;
    }
    var oracle = ORACLE.matcher(value);
    while (oracle.find()) {
      if (oracle.group(2).contains("/")) {
        return true;
      }
    }
    var keys = ASSIGNMENT.matcher(value);
    while (keys.find()) {
      if (sensitiveKey(keys.group(1))) {
        return true;
      }
    }
    return false;
  }

  public static String sanitize(String value) {
    if (value == null || value.isEmpty()) {
      return value;
    }
    var oracle = ORACLE.matcher(value);
    StringBuilder replaced = new StringBuilder();
    while (oracle.find()) {
      oracle.appendReplacement(replaced, java.util.regex.Matcher.quoteReplacement(
          oracle.group(2).contains("/") ? oracle.group(1) + MASK : oracle.group()));
    }
    oracle.appendTail(replaced);
    value = replaced.toString();
    StringBuilder safe = new StringBuilder();
    var keys = ASSIGNMENT.matcher(value);
    int copied = 0;
    while (keys.find()) {
      if (keys.start() < copied || !sensitiveKey(keys.group(1))) {
        continue;
      }
      int start = keys.end();
      int end = endOfSecret(value, start);
      safe.append(value, copied, start).append(MASK);
      copied = end;
    }
    return safe.append(value, copied, value.length()).toString();
  }

  private static int endOfSecret(String text, int start) {
    if (text.startsWith(MASK, start)) {
      return start + MASK.length();
    }
    if (start >= text.length()) {
      return start;
    }
    char opening = text.charAt(start);
    if (opening == '{' || opening == '\'' || opening == '"') {
      char closing = opening == '{' ? '}' : opening;
      for (int i = start + 1; i < text.length(); i++) {
        if (text.charAt(i) == '\\') {
          i++;
        } else if (text.charAt(i) == closing) {
          if (i + 1 < text.length() && text.charAt(i + 1) == closing) {
            i++;
          } else {
            return i + 1;
          }
        }
      }
      return text.length(); // malformed secret: retain no identifiable suffix
    }
    int end = start;
    while (end < text.length() && "&;,\r\n}".indexOf(text.charAt(end)) < 0) {
      if (Character.isWhitespace(text.charAt(end))) {
        var next = ASSIGNMENT.matcher(text);
        next.region(end, text.length());
        if (next.find() && text.substring(end, next.start()).isBlank()) {
          break;
        }
        // A JDBC URL starts a separate diagnostic field.
        if (text.substring(end).stripLeading().startsWith("jdbc:")) {
          break;
        }
      }
      end++;
    }
    return end;
  }

  public static Object sanitizeValue(Object value) {
    if (value instanceof String text) {
      return sanitize(text);
    }
    if (value instanceof java.util.List<?> values) {
      return values.stream().map(CredentialRedactor::sanitizeValue).toList();
    }
    if (value instanceof Map<?, ?> fields) {
      var safe = new LinkedHashMap<String, Object>();
      fields.forEach((key, item) -> safe.put(String.valueOf(key),
          sensitiveKey(String.valueOf(key)) && item != null ? MASK : sanitizeValue(item)));
      return safe;
    }
    return value;
  }
}
