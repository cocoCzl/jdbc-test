package com.jdbctest.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jdbctest.security.CredentialRedactor;
import org.junit.jupiter.api.Test;

class DmAdapterTest {

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
        "TEST_SCHEMA", "Test_Schema_8", "MixedCase", "develop", "DEVELOP", "A123"
    })
    void preservesEveryAcceptedSchemaExactly(String schema) {
        assertEquals(schema, new ExecutionScope(null, schema).namespace(SqlDialect.DM));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
        "", "123", "_schema", " schema", "schema ", "a.b", "a-b", "\"MixedCase\"",
        "a;DROP TABLE other", "模式", "a\n"
    })
    void rejectsNamesOutsideTheSharedSafeIdentifierPolicy(String schema) {
        assertThrows(IllegalArgumentException.class,
            () -> new ExecutionScope(null, schema).validate(SqlDialect.DM));
    }

    @Test
    void enforcesTheShared63CharacterLimit() {
        String schema = "A" + "_".repeat(62);
        assertEquals(schema, new ExecutionScope(null, schema).namespace(SqlDialect.DM));
        assertThrows(IllegalArgumentException.class,
            () -> new ExecutionScope(null, schema + "A").validate(SqlDialect.DM));
    }

    @Test
    void hasIndependentIdentityDialectAndAssets() {
        var adapter = AdapterRegistry.bundled().required("dm");
        assertEquals(DbType.DM, adapter.dbType().orElseThrow());
        assertEquals(SqlDialect.DM, adapter.dialect());
        assertEquals("dm", adapter.assets().directory());
        assertEquals("official", adapter.trust());
        assertEquals("OFFICIAL", adapter.metadata().status());
        var combinations = (java.util.List<?>)adapter.declaration().get("validated_combinations");
        assertEquals(1, combinations.size());
        var combination = (java.util.Map<?, ?>)combinations.getFirst();
        assertEquals("8.1.3.162", combination.get("database_version"));
        assertEquals("8.1.3.62", combination.get("driver_version"));
        assertEquals("COMPATIBLE_MODE=0", combination.get("database_mode"));
        assertEquals("21", combination.get("jdk"));
        assertTrue(adapter.name().contains("达梦"));
        assertTrue(adapter.name().contains("Dameng"));
        assertEquals("dm.jdbc.driver.DmDriver",
            ((java.util.Map<?, ?>) adapter.declaration().get("driver")).get("class"));
        assertTrue("DM DBMS".matches(
            adapter.metadataConstraints().get("expected_database_product_regex")));
        assertFalse("Oracle".matches(
            adapter.metadataConstraints().get("expected_database_product_regex")));
        assertTrue("dm.jdbc.driver.DmDriver".matches(
            adapter.metadataConstraints().get("expected_driver_name_regex")));
        assertThrows(IllegalArgumentException.class,
            () -> adapter.execution().requireDialect("oracle"));
    }

    @Test
    void usesExactAuthorizedSchemaWithoutInventingCatalogOrFoldingQuotedNames() {
        var scope = new ExecutionScope(null, "MixedCase");
        assertSame(scope, scope.validate(SqlDialect.DM));
        assertEquals("MixedCase", scope.namespace(SqlDialect.DM));
        assertEquals("DEVELOP", new ExecutionScope(null, "DEVELOP").namespace("dm"));
        assertFalse(SqlDialect.DM.requiresDatabase());
        assertEquals("schema", SqlDialect.DM.selection());
        assertThrows(IllegalArgumentException.class,
            () -> new ExecutionScope("unused", null).validate(SqlDialect.DM));
        assertEquals("\"MixedCase\"", SqlDialect.DM.quoteIdentifier("MixedCase", "\""));
    }

    @Test
    void dmSchemaUrlDoesNotRequireMysqlDatabasePathAndSecretsStayRedacted() {
        String url = "jdbc:dm://fixture.invalid:5236?schema=MixedCase";
        assertFalse(CredentialRedactor.containsCredentials(url));
        assertEquals(url, CredentialRedactor.sanitize(url));
        assertTrue(CredentialRedactor.containsCredentials(url + "&password=private"));
        assertFalse(CredentialRedactor.sanitize(url + "&password=private").contains("private"));
    }
}
