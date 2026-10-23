package com.jdbctest.connection;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;

class LockingBehaviorFixtureTest {

    @Test
    void recognizesDmLockErrorsOnlyForTheirExactDialectStateAndCode() {
        SQLException timeout = new SQLException("request execution timeout", "22000", -608);
        SQLException deadlock = new SQLException("deadlock", "22000", -6403);
        assertTrue(LockingBehaviorTest.isLockTimeout(timeout, "dm"));
        assertTrue(LockingBehaviorTest.isDeadlock(deadlock, "dm"));
        assertFalse(LockingBehaviorTest.isLockTimeout(timeout, "oracle"));
        assertFalse(LockingBehaviorTest.isDeadlock(deadlock, "oracle"));
        assertFalse(LockingBehaviorTest.isDeadlock(timeout, "dm"));
        assertFalse(LockingBehaviorTest.isLockTimeout(deadlock, "dm"));
        assertFalse(LockingBehaviorTest.isLockTimeout(
            new SQLException("unrelated", "HY000", -608), "dm"));
        assertFalse(LockingBehaviorTest.isDeadlock(
            new SQLException("unrelated", "HY000", -6403), "dm"));
        assertFalse(LockingBehaviorTest.isLockTimeout(
            new SQLException("unrelated", "22000", -6105), "dm"));
        assertFalse(LockingBehaviorTest.isDeadlock(null, "dm"));
    }

    @Test
    void preservesExistingDatabaseErrorRecognition() {
        assertTrue(LockingBehaviorTest.isLockTimeout(
            new SQLException("cancelled", "57014"), "postgresql"));
        assertTrue(LockingBehaviorTest.isDeadlock(
            new SQLException("deadlock", "40P01"), "postgresql"));
        assertTrue(LockingBehaviorTest.isDeadlock(
            new SQLException("deadlock", "HY000", 60), "oracle"));
        assertTrue(LockingBehaviorTest.isDeadlock(
            new SQLException("deadlock", "HY000", 1213), "mysql"));
        assertTrue(LockingBehaviorTest.isDeadlock(
            new SQLException("deadlock", "HY000", 1205), "sqlserver"));
        assertFalse(LockingBehaviorTest.isDeadlock(
            new SQLException("lock timeout", "HY000", 1205), "mysql"));
    }
}
