package com.jdbctest.catalog;

import java.util.List;

/**
 * Read-only metadata for the adapters packaged with the application.
 */
public final class AdapterCatalog {

    private AdapterCatalog() {
    }

    public static List<AdapterMetadata> list() {
        return AdapterRegistry.bundled().list().stream().map(AdapterDefinition::metadata).toList();
    }

    public static boolean contains(String id) {
        return AdapterRegistry.bundled().contains(id);
    }

    public record AdapterMetadata(String id, String name, String status) {

    }
}
