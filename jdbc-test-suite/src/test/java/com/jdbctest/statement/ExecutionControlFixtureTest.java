package com.jdbctest.statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ExecutionControlFixtureTest {

    @Test
    void mysqlSleepIsPartOfAQuerySoDocumentedInterruptionRaisesAnError() {
        assertEquals("SELECT 1 WHERE SLEEP(5) = 0", ExecutionControlTest.sleepSql("mysql", 5));
        assertEquals("SELECT 1 WHERE SLEEP(0) = 0", ExecutionControlTest.sleepSql("mysql", 0));
        assertEquals("SELECT pg_sleep(5)", ExecutionControlTest.sleepSql("postgresql", 5));
        assertThrows(IllegalStateException.class,
            () -> ExecutionControlTest.sleepSql("unknown", 5));
    }
}
