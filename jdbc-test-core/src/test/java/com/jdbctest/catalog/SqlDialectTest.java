package com.jdbctest.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SqlDialectTest {

    @Test
    void scopeUsesDeclaredBehaviorRatherThanProductIdentity() {
        for (String id : java.util.List.of("gaussdb", "mysql", "oracle", "postgresql",
            "sqlserver")) {
            SqlDialect dialect = AdapterExecutionConfig.load(id).sqlDialect();
            ExecutionScope scope =
                dialect == SqlDialect.ORACLE ? new ExecutionScope(null, "owned_scope")
                    : dialect == SqlDialect.MYSQL ? new ExecutionScope("owned_database", null)
                        : new ExecutionScope("owned_database", "owned_scope");
            assertEquals(scope.namespace(id), scope.namespace(dialect));
            assertEquals(dialect == SqlDialect.ORACLE ? "OWNED_SCOPE"
                    : dialect == SqlDialect.MYSQL ? "owned_database" : "owned_scope",
                scope.namespace(dialect));
            assertThrows(IllegalArgumentException.class,
                () -> new ExecutionScope(null, null).validate(dialect));
        }
        assertThrows(IllegalArgumentException.class,
            () -> new ExecutionScope("db", "different").validate(SqlDialect.MYSQL));
        assertThrows(IllegalArgumentException.class,
            () -> new ExecutionScope("db", "../schema").validate(SqlDialect.POSTGRESQL));
        assertThrows(IllegalArgumentException.class, () -> SqlDialect.fromCode("generic"));
        assertTrue(SqlDialect.SQL_SERVER.indexDropRequiresTable());
        assertEquals("[a]]b]", SqlDialect.SQL_SERVER.quoteIdentifier("a]b", "["));
    }
}
