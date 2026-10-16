package com.jdbctest.catalog;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class AdapterExecutionConfigTest {

    @Test
    void packagedOfficialManifestsCarryCompleteBooleanDeclarations() {
        for (String id : List.of("mysql", "postgresql", "oracle", "sqlserver", "gaussdb")) {
            var config = AdapterExecutionConfig.load(id);
            assertTrue(
                config.capabilities().keySet()
                    .containsAll(AdapterExecutionConfig.REQUIRED_CAPABILITIES),
                id);
            assertNotNull(config.identifierQuote());
            try (var input = getClass().getResourceAsStream("/adapters/" + id + "/adapter.json")) {
                Map<?, ?> manifest = new org.yaml.snakeyaml.Yaml().load(input);
                var identity = (Map<?, ?>) manifest.get("identity");
                assertEquals(identity.get("database_product_regex"),
                    config.metadataConstraints().get("expected_database_product_regex"));
                assertEquals(identity.get("driver_name_regex"),
                    config.metadataConstraints().get("expected_driver_name_regex"));
                var versions = (Map<?, ?>) manifest.get("supported_versions");
                for (String target : List.of("database", "driver")) {
                    var endpoints = (Map<?, ?>) versions.get(target);
                    for (String endpoint : List.of("min", "max")) {
                        assertEquals(endpoints.get(endpoint),
                            config.metadataConstraints().get(target + "_version_" + endpoint));
                    }
                }
            } catch (java.io.IOException error) {
                fail(error);
            }
        }
        assertTrue(AdapterExecutionConfig.load("postgresql").capabilities().get("array_type"));
        assertFalse(AdapterExecutionConfig.load("mysql").capabilities().get("array_type"));
    }

    @Test
    void missingAndInvalidDeclarationsAreConfigurationErrorsNotDisabledCapabilities() {
        assertThrows(IllegalArgumentException.class,
            () -> AdapterExecutionConfig.validateCapabilities(Map.of()));
        Map<String, Object> invalid = new HashMap<>(
            AdapterExecutionConfig.load("postgresql").capabilities());
        invalid.put("array_type", "false");
        assertThrows(IllegalArgumentException.class,
            () -> AdapterExecutionConfig.validateCapabilities(invalid));
        invalid.remove("array_type");
        assertThrows(IllegalArgumentException.class,
            () -> AdapterExecutionConfig.validateCapabilities(invalid));
    }

    @Test
    void disablingIsPreservedAndSnapshotsAreImmutable() {
        var values = new HashMap<>(AdapterExecutionConfig.load("postgresql").capabilities());
        values.put("array_type", false);
        var snapshot = AdapterExecutionConfig.validateCapabilities(values);
        values.put("array_type", true);
        assertFalse(snapshot.get("array_type"));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.put("array_type", true));
    }
}
