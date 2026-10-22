package com.jdbctest.catalog;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Execution metadata from the same manifest consumed by the Python CLI. No driver-inferred
 * overrides.
 */
public record AdapterExecutionConfig(String id, String assetId, String dialect,
    String identifierQuote,
    Map<String, Boolean> capabilities, Map<String, String> metadataConstraints) {

    public static final Set<String> REQUIRED_CAPABILITIES = Set.of(
        "savepoint", "callable_statement", "array_type", "ref_type", "struct_type", "rowid_type",
        "sqlxml", "rowset", "blobs", "clobs", "nclobs", "parameter_metadata",
        "updatable_result_set",
        "scrollable_result_set", "generated_keys", "multiple_result_sets", "statement_cancel",
        "query_timeout",
        "network_timeout", "client_info", "request_boundaries", "sharding", "connection_pool",
        "xa_data_source", "java_time", "lob_objects", "lob_locator_updates",
        "batch_generated_keys", "generated_keys_by_index", "callable_inout", "ref_cursor",
        "rowid_operations", "multiple_result_sequences", "data_source");

    public AdapterExecutionConfig {
        capabilities = validateCapabilities(capabilities);
        metadataConstraints = Map.copyOf(metadataConstraints);
    }

    public static AdapterExecutionConfig load(String id) {
        return AdapterRegistry.bundled().required(id).execution();
    }

    /** Resources, namespace rules and dialect form one supported execution configuration. */
    public AdapterExecutionConfig requireDialect(String requested) {
        if (requested != null && !sqlDialect().equals(SqlDialect.fromCode(requested))) {
            throw new IllegalArgumentException("ADAPTER_DIALECT_UNSUPPORTED");
        }
        return this;
    }

    public SqlDialect sqlDialect() {
        return SqlDialect.fromCode(dialect);
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
