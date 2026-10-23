package com.jdbctest.catalog;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class AdapterCatalogTest {

    @Test
    void packagedCatalogReadsExistingAdapterManifests() {
        assertEquals(7, AdapterCatalog.list().size());
        assertTrue(AdapterCatalog.contains("postgresql"));
        assertFalse(AdapterCatalog.contains("postgrsql"));
        AdapterCatalog.AdapterMetadata postgres = AdapterCatalog.list().stream()
            .filter(item -> item.id().equals("postgresql")).findFirst().orElseThrow();
        assertEquals("PostgreSQL", postgres.name());
        assertEquals("OFFICIAL", postgres.status());
    }
}
