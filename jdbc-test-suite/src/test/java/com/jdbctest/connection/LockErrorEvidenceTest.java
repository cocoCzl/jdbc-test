package com.jdbctest.connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;

class LockErrorEvidenceTest {

    @Test
    void absentSqlStateDoesNotInterruptTimeoutEvidence() {
        for (String state : new String[] {null, "", "   "}) {
            Map<String, String> entries = new HashMap<>();
            TestReporter reporter = entries::putAll;
            LockingBehaviorTest.reportLockError(reporter, "jdbc.lock.timeout",
                new SQLException("timeout", state, 1205));
            assertFalse(entries.containsKey("jdbc.lock.timeout.sqlState"));
            assertEquals("1205", entries.get("jdbc.lock.timeout.vendorCode"));
        }
    }

    @Test
    void presentSqlStateAndVendorCodeArePreserved() {
        Map<String, String> entries = new HashMap<>();
        TestReporter reporter = entries::putAll;
        LockingBehaviorTest.reportLockError(reporter, "jdbc.lock.deadlock",
            new SQLException("deadlock", "40001", 1213));
        assertEquals(Map.of("jdbc.lock.deadlock.sqlState", "40001",
            "jdbc.lock.deadlock.vendorCode", "1213"), entries);
    }
}
