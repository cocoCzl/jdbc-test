package com.jdbctest.catalog;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.Yaml;

/**
 * Execution metadata from the same manifest consumed by the Python CLI. No driver-inferred
 * overrides.
 */
public record AdapterExecutionConfig(String id, String assetId, String dialect, String identifierQuote,
    Map<String, Boolean> capabilities, Map<String, String> metadataConstraints) {

  public static final Set<String> REQUIRED_CAPABILITIES = Set.of(
      "savepoint", "callable_statement", "array_type", "ref_type", "struct_type", "rowid_type",
      "sqlxml", "rowset", "blobs", "clobs", "nclobs", "parameter_metadata", "updatable_result_set",
      "scrollable_result_set", "generated_keys", "multiple_result_sets", "statement_cancel",
      "query_timeout",
      "network_timeout", "client_info", "request_boundaries", "sharding", "connection_pool",
      "xa_data_source", "java_time", "lob_objects", "lob_locator_updates",
      "batch_generated_keys", "generated_keys_by_index", "callable_inout", "ref_cursor",
      "rowid_operations", "multiple_result_sequences", "data_source");

  public static AdapterExecutionConfig load(String id) {
    if (!AdapterCatalog.contains(id)) {
      throw new IllegalArgumentException("Unknown execution adapter");
    }
    try (InputStream input = AdapterExecutionConfig.class.getResourceAsStream(
        "/adapters/" + id + "/adapter.json")) {
      if (input == null) {
        throw new IllegalArgumentException("Missing adapter manifest");
      }
      Map<String, Object> manifest = new Yaml().load(input);
      Map<?, ?> assets = (Map<?, ?>) manifest.get("assets");
      Map<?, ?> identity = (Map<?, ?>) manifest.get("identity");
      Map<?, ?> versions = (Map<?, ?>) manifest.get("supported_versions");
      Map<String, String> constraints = new LinkedHashMap<>();
      constraints.put("expected_database_product_regex", required(identity, "database_product_regex"));
      constraints.put("expected_driver_name_regex", required(identity, "driver_name_regex"));
      for (String target : java.util.List.of("database", "driver")) {
        Object range = versions.get(target);
        if (range instanceof Map<?, ?> endpoints) {
          for (String endpoint : java.util.List.of("min", "max")) {
            Object value = endpoints.get(endpoint);
            if (value != null && !value.toString().isBlank()) {
              constraints.put(target + "_version_" + endpoint, value.toString());
            }
          }
        }
      }
      return new AdapterExecutionConfig(id,
          assets.get("directory") == null ? id : assets.get("directory").toString(),
          required(manifest, "dialect"), required(manifest, "identifier_quote"),
          validateCapabilities(manifest.get("capabilities")), Map.copyOf(constraints));
    } catch (java.io.IOException exception) {
      throw new IllegalStateException("Cannot read adapter manifest", exception);
    }
  }

  private static String required(Map<?, ?> fields, String name) {
    if (fields == null || !(fields.get(name) instanceof String value) || value.isBlank()) {
      throw new IllegalArgumentException("ADAPTER_DECLARATION_MISSING: " + name);
    }
    return value;
  }

  public static Map<String, Boolean> validateCapabilities(Object value) {
    if (!(value instanceof Map<?, ?> values) || !values.keySet()
        .containsAll(REQUIRED_CAPABILITIES)) {
      throw new IllegalArgumentException(
          "ADAPTER_CAPABILITIES_MISSING: all managed capabilities must be declared");
    }
    Map<String, Boolean> result = new LinkedHashMap<>();
    values.forEach((key, enabled) -> {
      if (!(key instanceof String name) || !(enabled instanceof Boolean flag)) {
        throw new IllegalArgumentException(
            "ADAPTER_CAPABILITIES_INVALID: declarations must be booleans");
      }
      result.put(name, flag);
    });
    return Map.copyOf(result);
  }
}
