package com.jdbctest.statement;

import static org.junit.jupiter.api.Assertions.*;

import com.jdbctest.config.ConfigLoader;
import com.jdbctest.extension.JdbcTestExtension;
import com.jdbctest.extension.RequiresFeature;
import java.sql.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(JdbcTestExtension.class)
@Tag("integration")
class ExecutionControlTest {

    private String sleepSql(int seconds) {
        return switch (ConfigLoader.load().db.dialect.toLowerCase(java.util.Locale.ROOT)) {
            case "oracle" -> "BEGIN DBMS_SESSION.SLEEP(" + seconds + "); END;";
            case "mysql" -> "SELECT SLEEP(" + seconds + ")";
            case "sqlserver" -> "WAITFOR DELAY '00:00:0" + seconds + "'";
            case "postgresql", "gaussdb" -> "SELECT pg_sleep(" + seconds + ")";
            default -> throw new IllegalStateException("Adapter has no bounded sleep fixture");
        };
    }

    private void assertConnectionUsable(Connection connection) throws SQLException {
        String sql = ConfigLoader.load().db.isDialect("oracle") ? "SELECT 1 FROM DUAL" : "SELECT 1";
        try (Statement check = connection.createStatement();
            ResultSet rows = check.executeQuery(sql)) {
            assertTrue(rows.next());
            assertEquals(1, rows.getInt(1));
        }
    }

    @Test
    @RequiresFeature("query_timeout")
    @Timeout(15)
    @DisplayName("QueryTimeout 实际中断慢语句且连接仍可使用")
    void testActualQueryTimeout(Connection connection) throws SQLException {
        try (Statement sql = connection.createStatement()) {
            sql.execute(
                sleepSql(0)); // Invalid SQL or missing privileges must fail before the assertion.
            sql.setQueryTimeout(1);
            long started = System.nanoTime();
            SQLException error = assertThrows(SQLException.class, () -> sql.execute(sleepSql(5)));
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertTrue(elapsed >= 300 && elapsed < 5000,
                "Timeout must interrupt the five-second work");
            assertTrue(error instanceof SQLTimeoutException || "57014".equals(error.getSQLState())
                    || "HYT00".equals(error.getSQLState()) || "HYT01".equals(error.getSQLState())
                    || error.getErrorCode() == 1013
                    || error.getClass().getSimpleName().equals("MySQLTimeoutException"),
                "Expected timeout evidence, not an arbitrary SQL failure: " + error.getClass()
                    .getName());
        }
        assertConnectionUsable(connection);
    }

    @Test
    @RequiresFeature("statement_cancel")
    @Timeout(15)
    @DisplayName("另一个线程取消慢语句并释放取消线程")
    void testCancelRunningStatement(Connection connection) throws Exception {
        ScheduledExecutorService canceller = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon(true).name("jdbc-scenario-cancel").factory());
        try (Statement sql = connection.createStatement()) {
            sql.execute(sleepSql(0));
            ScheduledFuture<Void> cancellation = canceller.schedule(() -> {
                sql.cancel();
                return null;
            }, 500, TimeUnit.MILLISECONDS);
            try {
                long started = System.nanoTime();
                SQLException error = assertThrows(SQLException.class,
                    () -> sql.execute(sleepSql(5)));
                cancellation.get(5, TimeUnit.SECONDS);
                long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                assertTrue(elapsed >= 300 && elapsed < 5000,
                    "Cancellation must interrupt ongoing work");
                assertTrue("57014".equals(error.getSQLState()) || error.getErrorCode() == 1013
                        || error.getClass().getSimpleName().equals("MySQLStatementCancelledException")
                        || ("HY008".equals(error.getSQLState())),
                    "Expected cancellation evidence: " + error.getClass().getName());
            } finally {
                cancellation.cancel(false);
            }
        } finally {
            canceller.shutdownNow();
            assertTrue(canceller.awaitTermination(5, TimeUnit.SECONDS), "Cancel thread must exit");
        }
        assertConnectionUsable(connection);
    }
}
