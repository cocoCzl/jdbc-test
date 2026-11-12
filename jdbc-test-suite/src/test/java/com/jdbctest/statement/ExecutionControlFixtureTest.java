package com.jdbctest.statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;

class ExecutionControlFixtureTest {

    @Test
    void unverifiedSleepInterruptionRetainsFailedAssertionAndDoesNotHideCancelErrors()
        throws Exception {
        Statement sql = mock(Statement.class);
        var reporter = mock(TestReporter.class);
        var failure = assertThrows(
            com.jdbctest.extension.JdbcContracts.ContractApplicabilityAssertionError.class,
            () -> ExecutionControlTest.executeAndObserveCancellation(sql, "slow",
                CompletableFuture.completedFuture(null), reporter, false));
        org.junit.jupiter.api.Assertions.assertInstanceOf(
            org.opentest4j.AssertionFailedError.class, failure.getCause());
        verify(sql).execute("slow");
        var cancel = new SQLException("cancel rejected", "HY000", 77);
        var failedCancel = assertThrows(ExecutionException.class,
            () -> ExecutionControlTest.executeAndObserveCancellation(sql, "slow",
                CompletableFuture.failedFuture(cancel), reporter, false));
        assertSame(cancel, failedCancel.getCause());
    }

    @Test
    void normalExecutionStillObservesAndPreservesTheCancelThreadFailure() throws Exception {
        Statement sql = mock(Statement.class);
        TestReporter reporter = mock(TestReporter.class);
        var original = new SQLException("cancel rejected", "HY000", 77);
        var cancellation = CompletableFuture.failedFuture(original);
        var failure = assertThrows(ExecutionException.class,
            () -> ExecutionControlTest.executeAndObserveCancellation(sql, "slow", cancellation,
                reporter));
        assertSame(original, failure.getCause());
        verify(sql).execute("slow");
        verify(reporter).publishEntry("jdbc.cancel.callOutcome", "FAILED");
    }

    @Test
    void normalExecutionAndSuccessfulCancelStillFailTheInterruptionAssertion() {
        Statement sql = mock(Statement.class);
        TestReporter reporter = mock(TestReporter.class);
        assertThrows(AssertionError.class,
            () -> ExecutionControlTest.executeAndObserveCancellation(sql, "slow",
                CompletableFuture.completedFuture(null), reporter));
        verify(reporter).publishEntry("jdbc.cancel.callOutcome", "RETURNED");
    }

    @Test
    void preservesBothExecutionAndCancelFailuresWithoutPassingTheTest() throws Exception {
        Statement sql = mock(Statement.class);
        var execution = new SQLException("execution interrupted", "HY008", 78);
        var cancel = new SQLException("cancel failed", "HY000", 79);
        when(sql.execute("slow")).thenThrow(execution);
        var failure = assertThrows(SQLException.class,
            () -> ExecutionControlTest.executeAndObserveCancellation(sql, "slow",
                CompletableFuture.failedFuture(cancel), mock(TestReporter.class)));
        assertSame(execution, failure);
        assertSame(cancel, failure.getSuppressed()[0].getCause());
    }

    @Test
    void successfulInterruptionRetainsOriginalSqlException() throws Exception {
        Statement sql = mock(Statement.class);
        var execution = new SQLException("interrupted", "HY008", 78);
        when(sql.execute("slow")).thenThrow(execution);
        assertSame(execution, ExecutionControlTest.executeAndObserveCancellation(sql, "slow",
            CompletableFuture.completedFuture(null), mock(TestReporter.class)));
    }

    @Test
    void cancellationWaitIsBoundedAndInterruptStatusIsRestored() throws Exception {
        Statement sql = mock(Statement.class);
        var cancellation = mock(java.util.concurrent.Future.class);
        when(cancellation.get(5, java.util.concurrent.TimeUnit.SECONDS))
            .thenThrow(new java.util.concurrent.TimeoutException("bounded wait"));
        assertThrows(java.util.concurrent.TimeoutException.class,
            () -> ExecutionControlTest.executeAndObserveCancellation(sql, "slow", cancellation,
                mock(TestReporter.class)));
        org.mockito.Mockito.doThrow(new InterruptedException("interrupted wait"))
            .when(cancellation).get(5, java.util.concurrent.TimeUnit.SECONDS);
        try {
            assertThrows(InterruptedException.class,
                () -> ExecutionControlTest.executeAndObserveCancellation(sql, "slow", cancellation,
                    mock(TestReporter.class)));
            org.junit.jupiter.api.Assertions.assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void shutdownWaitIsBoundedAndCannotReplaceOriginalFailure() throws Exception {
        var executor = mock(java.util.concurrent.ExecutorService.class);
        var primary = new SQLException("original", "HY008", 78);
        ExecutionControlTest.stopCanceller(executor, primary);
        verify(executor).shutdownNow();
        verify(executor).awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
        org.junit.jupiter.api.Assertions.assertInstanceOf(AssertionError.class,
            primary.getSuppressed()[0]);
        assertThrows(AssertionError.class, () -> ExecutionControlTest.stopCanceller(executor, null));
    }

    @Test
    void mysqlSleepIsPartOfAQuerySoDocumentedInterruptionRaisesAnError() {
        assertEquals("SELECT 1 WHERE SLEEP(5) = 0", ExecutionControlTest.sleepSql("mysql", 5));
        assertEquals("SELECT 1 WHERE SLEEP(0) = 0", ExecutionControlTest.sleepSql("mysql", 0));
        assertEquals("SELECT pg_sleep(5)", ExecutionControlTest.sleepSql("postgresql", 5));
        assertEquals("CALL DBMS_LOCK.SLEEP(5)", ExecutionControlTest.sleepSql("dm", 5));
        assertEquals("CALL DBMS_LOCK.SLEEP(0)", ExecutionControlTest.sleepSql("dm", 0));
        assertThrows(IllegalStateException.class,
            () -> ExecutionControlTest.sleepSql("unknown", 5));
    }
}
