package com.jdbctest.catalog;

import java.util.Map;
import java.util.Optional;

/**
 * Validated metadata. The retained JSON is immutable, including unconsumed extension fields.
 */
public record AdapterDefinition(String id, String name, String version, String revision,
    String trust, Optional<DbType> dbType, SqlDialect dialect, String identifierQuote,
    Assets assets, Map<String, Boolean> capabilities, Map<String, String> metadataConstraints,
    Map<String, Object> declaration) {

    public AdapterDefinition {
        capabilities = Map.copyOf(capabilities);
        metadataConstraints = Map.copyOf(metadataConstraints);
        declaration = AdapterManifest.immutableObject(declaration);
    }

    public AdapterCatalog.AdapterMetadata metadata() {
        return new AdapterCatalog.AdapterMetadata(id, name,
            "official".equals(trust) ? "OFFICIAL" : "CANDIDATE");
    }

    public AdapterExecutionConfig execution() {
        return new AdapterExecutionConfig(id, assets.directory(), dialect.code(), identifierQuote,
            capabilities, metadataConstraints);
    }

    public record Assets(String ddl, String dml, String pool, String directory,
        java.util.List<String> areas) {

        public Assets {
            areas = java.util.List.copyOf(areas);
        }
    }
}
