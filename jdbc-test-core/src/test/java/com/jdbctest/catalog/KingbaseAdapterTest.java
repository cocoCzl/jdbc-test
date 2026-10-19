package com.jdbctest.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class KingbaseAdapterTest {

    @Test
    void officialKeepsProductDriverAndInfrastructureIndependent() {
        var adapter = AdapterRegistry.bundled().required("kingbase");
        assertEquals(DbType.KINGBASE, adapter.dbType().orElseThrow());
        assertEquals("OFFICIAL", adapter.metadata().status());
        assertEquals(SqlDialect.POSTGRESQL, adapter.dialect());
        assertEquals("postgresql", adapter.assets().directory());
        var combinations = (List<?>) adapter.declaration().get("validated_combinations");
        assertEquals(1, combinations.size());
        var combination = (Map<?, ?>) combinations.getFirst();
        assertEquals("12.1", combination.get("database_version"));
        assertEquals("V008R006B0001", combination.get("driver_version"));
        assertEquals("V009R002C016", combination.get("server_version"));
        assertEquals("oracle", combination.get("database_mode"));
        var driver = (Map<?, ?>) adapter.declaration().get("driver");
        assertEquals("com.kingbase8.Driver", driver.get("class"));
        assertEquals(Map.of("kind", "local", "path", "local-drivers/kingbase"),
            driver.get("source"));
        assertEquals(Map.of("database", Map.of(), "driver", Map.of()),
            adapter.declaration().get("supported_versions"));
        assertTrue(adapter.metadataConstraints().keySet().stream()
            .noneMatch(key -> key.contains("version")));
        assertEquals("existing", ((Map<?, ?>) adapter.declaration().get("namespace")).get("mode"));
        assertFalse(adapter.capabilities().get("callable_statement"));
        assertFalse(adapter.capabilities().get("array_type"));
        assertTrue(adapter.declaration().containsKey("qualification"));
    }

    @Test
    void anchoredIdentityDoesNotAdmitPostgresqlOrUnrelatedDrivers() {
        var constraints = AdapterExecutionConfig.load("kingbase").metadataConstraints();
        var product = Pattern.compile(constraints.get("expected_database_product_regex"),
            Pattern.CASE_INSENSITIVE);
        var driver = Pattern.compile(constraints.get("expected_driver_name_regex"),
            Pattern.CASE_INSENSITIVE);
        assertTrue(product.matcher("KingbaseES").find());
        assertTrue(driver.matcher("Kingbase8 JDBC Driver").find());
        for (String other : List.of("PostgreSQL", "GaussDB", "KingbaseES-compatible PostgreSQL")) {
            assertFalse(product.matcher(other).find(), other);
        }
        for (String other : List.of("PostgreSQL JDBC Driver", "Other Kingbase8 JDBC Driver")) {
            assertFalse(driver.matcher(other).find(), other);
        }
    }
}
