package com.jdbctest.connection;

import static org.junit.jupiter.api.Assertions.*;

import com.jdbctest.config.ConfigLoader;
import com.jdbctest.extension.*;
import java.sql.*;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(JdbcTestExtension.class)
@Tag("integration")
@RequiresFeature("query_timeout")
@UseSqlScripts(ddl = "locking_behavior_ddl.sql")
class LockingBehaviorTest {

    private void seed() throws SQLException {
        try (Connection connection = JdbcContext.getConnection();
            Statement sql = connection.createStatement()) {
            sql.executeUpdate(JdbcContext.sql(
                "INSERT INTO {{locking_behavior}} (id, value) VALUES (1, 1)"));
            sql.executeUpdate(JdbcContext.sql(
                "INSERT INTO {{locking_behavior}} (id, value) VALUES (2, 2)"));
        }
    }

    private void delete() throws SQLException {
        try (Connection connection = JdbcContext.getConnection();
            Statement sql = connection.createStatement()) {
            sql.executeUpdate(
                JdbcContext.sql("DELETE FROM {{locking_behavior}} WHERE id IN (1, 2)"));
        }
    }

    private void update(Statement sql, int id) throws SQLException {
        assertEquals(1, sql.executeUpdate(JdbcContext.sql(
            "UPDATE {{locking_behavior}} SET value = value + 10 WHERE id = " + id)));
    }

    private void assertOriginalRows() throws SQLException {
        try (Connection connection = JdbcContext.getConnection();
            Statement sql = connection.createStatement();
            ResultSet rows = sql.executeQuery(JdbcContext.sql(
                "SELECT id, value FROM {{locking_behavior}} ORDER BY id"))) {
            for (int id : List.of(1, 2)) {
                assertTrue(rows.next());
                assertEquals(id, rows.getInt(1));
                assertEquals(id, rows.getInt(2));
            }
            assertFalse(rows.next());
        }
    }

    @Test
    @Timeout(20)
    @DisplayName("同一行锁等待超时后回滚，释放锁后连接可继续写入")
    void testLockWaitTimeoutAndRecovery(TestReporter reporter) throws SQLException {
        seed();
        try (Connection locker = JdbcContext.getConnection();
            Connection waiter = JdbcContext.getConnection();
            Statement lock = locker.createStatement(); Statement wait = waiter.createStatement()) {
            locker.setAutoCommit(false);
            waiter.setAutoCommit(false);
            try {
                update(lock, 1);
                wait.setQueryTimeout(1);
                long started = System.nanoTime();
                SQLException error = assertThrows(SQLException.class, () -> update(wait, 1));
                long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                reporter.publishEntry("jdbc.lock.timeout.sqlState",
                    java.util.Objects.toString(error.getSQLState(), ""));
                reporter.publishEntry("jdbc.lock.timeout.vendorCode",
                    Integer.toString(error.getErrorCode()));
                assertTrue(elapsed >= 300 && elapsed < 6000,
                    "The blocked update must fail within bounds");
                assertTrue(isLockTimeout(error, ConfigLoader.load().db.dialect),
                    "Require lock-wait/timeout evidence, not an arbitrary SQL error");
                waiter.rollback();
                locker.rollback();
                wait.setQueryTimeout(5);
                update(wait, 1);
                waiter.rollback();
            } finally {
                try {
                    locker.rollback();
                } finally {
                    waiter.rollback();
                }
            }
            assertOriginalRows();
        } finally {
            delete();
        }
    }

    private SQLException crossLock(int first, int second, CountDownLatch acquired)
        throws Exception {
        try (Connection connection = JdbcContext.getConnection();
            Statement sql = connection.createStatement()) {
            connection.setAutoCommit(false);
            sql.setQueryTimeout(8);
            try {
                update(sql, first);
                acquired.countDown();
                assertTrue(acquired.await(5, TimeUnit.SECONDS),
                    "Both transactions must hold their first row");
                try {
                    update(sql, second);
                    return null;
                } catch (SQLException error) {
                    return error;
                }
            } finally {
                connection.rollback();
            }
        }
    }

    static boolean isLockTimeout(SQLException error, String dialect) {
        return error instanceof SQLTimeoutException || "57014".equals(error.getSQLState())
            || "HYT00".equals(error.getSQLState()) || "HYT01".equals(error.getSQLState())
            || error.getErrorCode() == 1205 || error.getErrorCode() == 1013
            || error.getClass().getSimpleName().equals("MySQLTimeoutException")
            || ("dm".equals(dialect) && "22000".equals(error.getSQLState())
            && error.getErrorCode() == -608);
    }

    static boolean isDeadlock(SQLException error, String dialect) {
        return error != null && ("40P01".equals(error.getSQLState())
            || "40001".equals(error.getSQLState()) || error.getErrorCode() == 60
            || error.getErrorCode() == 1213 || (error.getErrorCode() == 1205
            && "sqlserver".equals(dialect)) || ("dm".equals(dialect)
            && "22000".equals(error.getSQLState()) && error.getErrorCode() == -6403));
    }

    @Test
    @Timeout(30)
    @DisplayName("两个事务反向持锁形成真实死锁并释放所有线程和行锁")
    void testCrossLockDeadlockAndRecovery(TestReporter reporter) throws Exception {
        seed();
        ExecutorService threads = Executors.newFixedThreadPool(2,
            Thread.ofPlatform().daemon(true).name("jdbc-deadlock-", 0).factory());
        try {
            CountDownLatch acquired = new CountDownLatch(2);
            Future<SQLException> first = threads.submit(() -> crossLock(1, 2, acquired));
            Future<SQLException> second = threads.submit(() -> crossLock(2, 1, acquired));
            SQLException one = first.get(15, TimeUnit.SECONDS);
            SQLException two = second.get(15, TimeUnit.SECONDS);
            for (SQLException error : new SQLException[] {one, two}) {
                if (error != null) {
                    reporter.publishEntry("jdbc.lock.deadlock.sqlState",
                        java.util.Objects.toString(error.getSQLState(), ""));
                    reporter.publishEntry("jdbc.lock.deadlock.vendorCode",
                        Integer.toString(error.getErrorCode()));
                }
            }
            String dialect = ConfigLoader.load().db.dialect;
            assertTrue(isDeadlock(one, dialect) || isDeadlock(two, dialect),
                "At least one branch must report a deadlock");
            assertTrue(one == null || isDeadlock(one, dialect),
                "First branch must succeed or report deadlock");
            assertTrue(two == null || isDeadlock(two, dialect),
                "Second branch must succeed or report deadlock");
            assertOriginalRows();
            try (Connection connection = JdbcContext.getConnection();
                Statement sql = connection.createStatement()) {
                connection.setAutoCommit(false);
                sql.setQueryTimeout(5);
                try {
                    update(sql, 1);
                    update(sql, 2);
                } finally {
                    connection.rollback();
                }
            }
        } finally {
            threads.shutdownNow();
            try {
                assertTrue(threads.awaitTermination(10, TimeUnit.SECONDS),
                    "All lock-test threads must exit");
            } finally {
                if (threads.isTerminated()) {
                    delete();
                }
            }
        }
    }
}
