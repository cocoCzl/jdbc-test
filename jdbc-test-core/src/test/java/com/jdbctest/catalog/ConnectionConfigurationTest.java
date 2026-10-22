package com.jdbctest.catalog;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ConnectionConfigurationTest {

    @Test
    void scopesFollowResourcesRatherThanDatabaseCompatibilityNames() {
        assertDoesNotThrow(() -> new ExecutionScope("test", null).validate("mysql"));
        assertThrows(IllegalArgumentException.class,
            () -> new ExecutionScope("test", "other").validate("mysql"));
        assertDoesNotThrow(() -> new ExecutionScope("test", "owned").validate("postgresql"));
        assertThrows(IllegalArgumentException.class,
            () -> new ExecutionScope(null, "owned").validate("postgresql"));
        assertDoesNotThrow(() -> new ExecutionScope(null, "OWNED").validate("oracle"));
        assertThrows(IllegalArgumentException.class,
            () -> new ExecutionScope("service", null).validate("oracle"));
        assertThrows(IllegalArgumentException.class,
            () -> new ExecutionScope(null, "owned").validate("kingbase"));
        assertEquals("owned", new ExecutionScope("test", "owned").namespace("kingbase"));
    }

    @Test
    void dialectSelectionCannotKeepIncompatiblePreparationOrCleanupResources() {
        var kingbase = AdapterExecutionConfig.load("kingbase");
        assertSame(kingbase, kingbase.requireDialect("postgresql"));
        assertEquals("kingbase", kingbase.assetId());
        assertThrows(IllegalArgumentException.class, () -> kingbase.requireDialect("oracle"));
        assertThrows(IllegalArgumentException.class,
            () -> AdapterExecutionConfig.load("mysql").requireDialect("postgresql"));
        assertEquals(SqlDialect.SQL_SERVER,
            AdapterExecutionConfig.load("sqlserver").requireDialect(null).sqlDialect());
        assertEquals(SqlDialect.SQL_SERVER,
            AdapterExecutionConfig.load("sqlserver").requireDialect("sqlserver").sqlDialect());
    }
}
