package com.jdbctest.catalog;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One metadata validation boundary; SQL/evidence file existence belongs to packaging/CLI.
 */
public final class AdapterManifest {
  private static final Set<String> AREAS = Set.of("connection", "statement", "preparedstatement",
      "callablestatement", "resultset", "metadatatest", "savepoint", "rowset", "blobclob",
      "sqlxml", "advancedtype");

  private AdapterManifest() {
  }

  public static AdapterDefinition read(InputStream input) throws IOException {
    Map<String, Object> fields = object(AdapterJson.read(input), "root");
    String id = id(fields.get("id"));
    String name = text(fields, "name");
    String version = text(fields, "version");
    String revision = fields.containsKey("revision") ? text(fields, "revision") : "release";
    String trust = choice(fields, "trust", Set.of("official", "candidate", "local"));
    SqlDialect dialect = SqlDialect.fromCode(text(fields, "dialect"));
    String quote = fields.containsKey("identifier_quote") ? text(fields, "identifier_quote") : "\"";
    if (!Set.of("\"", "`", "[").contains(quote)) {
      throw invalid("identifier_quote");
    }
    if (fields.containsKey("db_type")) {
      text(fields, "db_type");
    }
    var type = DbType.fromCode(fields.containsKey("db_type") ? text(fields, "db_type") : id);
    Map<String, Object> driver = object(fields.get("driver"), "driver");
    text(driver, "class");
    Map<String, Object> source = object(driver.get("source"), "driver.source");
    String kind = choice(source, "kind", Set.of("maven", "local"));
    text(source, "maven".equals(kind) ? "coordinate" : "path");

    Map<String, Object> assets = object(fields.get("assets"), "assets");
    String ddl = resourcePath(text(assets, "ddl"));
    String dml = resourcePath(text(assets, "dml"));
    String pool = resourcePath(assets.containsKey("pool") ? text(assets, "pool") : "pool");
    String directory = assets.containsKey("directory") ? id(assets.get("directory")) : id;
    List<String> areas = strings(assets.get("areas"), "assets.areas");
    if (!areas.containsAll(AREAS) || areas.stream().anyMatch(area -> !area.matches("[a-z][a-z0-9_]*"))) {
      throw invalid("assets.areas");
    }
    Map<String, Boolean> capabilities = AdapterExecutionConfig.validateCapabilities(
        fields.get("capabilities"));
    Map<String, String> constraints = new LinkedHashMap<>();
    Map<String, Object> identity = object(fields.get("identity"), "identity");
    for (String key : List.of("database_product_regex", "driver_name_regex")) {
      String regex = text(identity, key);
      try {
        Pattern.compile(regex);
      } catch (java.util.regex.PatternSyntaxException exception) {
        throw invalid("identity." + key);
      }
      constraints.put("expected_" + key, regex);
    }
    Map<String, Object> versions = object(fields.get("supported_versions"), "supported_versions");
    for (String target : List.of("database", "driver")) {
      Map<String, Object> range = object(versions.getOrDefault(target, Map.of()), target);
      validateRange(range, target);
      for (String endpoint : List.of("min", "max")) {
        String value = (String) range.getOrDefault(endpoint, "");
        if (!value.isEmpty()) {
          constraints.put(target + "_version_" + endpoint, value);
        }
      }
    }
    Map<String, Object> namespace = object(fields.get("namespace"), "namespace");
    String mode = choice(namespace, "mode", Set.of("auto", "existing"));
    if (namespace.containsKey("selection")) {
      String selection = choice(namespace, "selection", Set.of("catalog", "schema", "none"));
      if (!"none".equals(selection) && !dialect.selection().equals(selection)) {
        throw invalid("namespace.selection");
      }
    }
    if (namespace.containsKey("drop_on_exit") && !(namespace.get("drop_on_exit") instanceof Boolean)) {
      throw invalid("namespace.drop_on_exit");
    }
    if ("auto".equals(mode)) {
      text(namespace, "create_sql");
    }
    if (Boolean.TRUE.equals(namespace.get("drop_on_exit"))) {
      text(namespace, "drop_sql");
    }
    List<String> privileges = strings(fields.get("minimum_privileges"), "minimum_privileges");
    List<String> covered = new ArrayList<>();
    if (namespace.containsKey("create_privilege")) {
      covered.add(text(namespace, "create_privilege"));
    }
    for (Object item : array(fields.get("privilege_checks"), "privilege_checks")) {
      Map<String, Object> check = object(item, "privilege_checks[]");
      covered.add(text(check, "privilege"));
      text(check, "sql");
      if (check.containsKey("cleanup_sql") && !(check.get("cleanup_sql") instanceof String)) {
        throw invalid("privilege_checks.cleanup_sql");
      }
    }
    if (!covered.containsAll(privileges)) {
      throw invalid("minimum_privileges");
    }
    List<?> combinations = array(fields.get("validated_combinations"), "validated_combinations");
    if ("official".equals(trust) && combinations.isEmpty()) {
      throw invalid("validated_combinations");
    }
    for (Object item : combinations) {
      Map<String, Object> combination = object(item, "validated_combinations[]");
      text(combination, "database_version");
      text(combination, "driver_version");
      resourcePath(text(combination, "evidence"));
    }
    for (Object item : array(fields.get("known_differences"), "known_differences")) {
      Map<String, Object> difference = object(item, "known_differences[]");
      for (String key : List.of("scenario_id", "reason", "issue_url", "review_after", "trust")) {
        text(difference, key);
      }
      choice(difference, "trust", Set.of("official", "candidate", "local"));
      for (String target : List.of("database_version", "driver_version")) {
        Object range = difference.get(target);
        if (range instanceof Map<?, ?>) {
          validateRange(object(range, target), target);
        } else if (!(range instanceof String value) || value.isBlank()) {
          throw invalid(target);
        }
      }
      try {
        LocalDate.parse(text(difference, "review_after"));
      } catch (java.time.format.DateTimeParseException exception) {
        throw invalid("known_differences.review_after");
      }
      if (!text(difference, "issue_url").matches("https?://.+")) {
        throw invalid("known_differences.issue_url");
      }
    }
    return new AdapterDefinition(id, name, version, revision, trust, type, dialect, quote,
        new AdapterDefinition.Assets(ddl, dml, pool, directory, areas), capabilities, constraints,
        fields);
  }

  static String id(Object value) {
    if (!(value instanceof String text) || !text.matches("[a-z][a-z0-9_-]{1,31}")) {
      throw invalid("id");
    }
    return text;
  }

  static String resourcePath(String path) {
    if (!path.matches("[A-Za-z0-9_-]+(?:[./][A-Za-z0-9_-]+)*")
        || path.contains("..") || path.contains(":")) {
      throw invalid("resource path");
    }
    return path;
  }

  private static void validateRange(Map<String, Object> range, String name) {
    for (String endpoint : List.of("min", "max")) {
      Object value = range.getOrDefault(endpoint, "");
      if (!(value instanceof String version) || !version.matches("(?:[0-9]+(?:\\.[0-9]+)*)?")) {
        throw invalid(name + "." + endpoint);
      }
    }
    String min = (String) range.getOrDefault("min", "");
    String max = (String) range.getOrDefault("max", "");
    if (!min.isEmpty() && !max.isEmpty() && compareVersions(min, max) > 0) {
      throw invalid(name + " range");
    }
  }

  private static int compareVersions(String first, String second) {
    String[] left = first.split("\\.");
    String[] right = second.split("\\.");
    for (int index = 0; index < Math.max(left.length, right.length); index++) {
      int order = new BigInteger(index < left.length ? left[index] : "0")
          .compareTo(new BigInteger(index < right.length ? right[index] : "0"));
      if (order != 0) {
        return order;
      }
    }
    return 0;
  }

  private static String text(Map<String, Object> fields, String name) {
    if (!(fields.get(name) instanceof String value) || value.isBlank()) {
      throw invalid(name);
    }
    return value;
  }

  private static String choice(Map<String, Object> fields, String name, Set<String> values) {
    String value = text(fields, name);
    if (!values.contains(value)) {
      throw invalid(name);
    }
    return value;
  }

  static Map<String, Object> object(Object value, String name) {
    if (!(value instanceof Map<?, ?> fields)) {
      throw invalid(name);
    }
    Map<String, Object> result = new LinkedHashMap<>();
    fields.forEach((key, item) -> {
      if (!(key instanceof String text)) {
        throw invalid(name);
      }
      result.put(text, item);
    });
    return result;
  }

  private static List<?> array(Object value, String name) {
    if (!(value instanceof List<?> items)) {
      throw invalid(name);
    }
    return items;
  }

  private static List<String> strings(Object value, String name) {
    List<String> result = new ArrayList<>();
    for (Object item : array(value, name)) {
      if (!(item instanceof String text) || text.isBlank() || result.contains(text)) {
        throw invalid(name);
      }
      result.add(text);
    }
    return List.copyOf(result);
  }

  static Map<String, Object> immutableObject(Map<String, Object> fields) {
    Map<String, Object> result = new LinkedHashMap<>();
    fields.forEach((key, value) -> result.put(key, immutable(value)));
    return Collections.unmodifiableMap(result);
  }

  private static Object immutable(Object value) {
    if (value instanceof Map<?, ?> map) {
      return immutableObject(object(map, "extension"));
    }
    if (value instanceof List<?> list) {
      List<Object> result = new ArrayList<>();
      list.forEach(item -> result.add(immutable(item)));
      return Collections.unmodifiableList(result);
    }
    if (value == null || value instanceof String || value instanceof Boolean
        || value instanceof BigInteger || value instanceof java.math.BigDecimal) {
      return value;
    }
    if (value instanceof Number number) {
      return new java.math.BigDecimal(number.toString());
    }
    throw invalid("extension");
  }

  private static IllegalArgumentException invalid(String field) {
    return new IllegalArgumentException("ADAPTER_DECLARATION_INVALID: " + field);
  }
}
