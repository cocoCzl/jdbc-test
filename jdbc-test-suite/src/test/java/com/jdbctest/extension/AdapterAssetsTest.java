package com.jdbctest.extension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jdbctest.catalog.AdapterRegistry;
import org.junit.jupiter.api.Test;

class AdapterAssetsTest {

    @Test
    void locatorFixtureRepresentationMatchesTheDeclaredDialectAcrossAssetBundles() throws Exception {
        for (var adapter : AdapterRegistry.bundled().list()) {
            String path = adapter.assets().ddl() + "/" + adapter.assets().directory()
                + "/lob_locator_ddl.sql";
            try (var input = getClass().getClassLoader().getResourceAsStream(path)) {
                assertNotNull(input, path);
                String ddl = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                if (java.util.Set.of("postgresql", "gaussdb")
                    .contains(adapter.assets().directory())) {
                    assertTrue(ddl.contains("binary_data OID") && ddl.contains("text_data OID"),
                        path + " must use locator OIDs, not BYTEA/TEXT stream storage");
                } else {
                    assertFalse(ddl.contains(" OID"), path);
                }
            }
        }
    }

    @Test
    void registeredAssetBundlesExistOnClasspathWithoutSourceWorkingDirectory() throws Exception {
        for (var adapter : AdapterRegistry.bundled().list()) {
            for (String area : adapter.assets().areas()) {
                if ("sqlxml".equals(area) && !adapter.capabilities().get("sqlxml")) {
                    continue;
                }
                for (String root : java.util.List.of(adapter.assets().ddl(),
                    adapter.assets().dml())) {
                    String name = root + "/" + adapter.assets().directory() + "/" + area
                        + ("ddl".equals(root) ? "_ddl.sql" : "_dml.sql");
                    try (var input = getClass().getClassLoader().getResourceAsStream(name)) {
                        assertNotNull(input, name);
                    }
                }
            }
            assertFalse(JdbcContext.loadPoolConfig(adapter.assets().directory(), "classpath:pool")
                .isEmpty());
        }
        assertThrows(IllegalArgumentException.class,
            () -> JdbcContext.loadPoolConfig("missing-adapter", "classpath:pool"));
    }
}
