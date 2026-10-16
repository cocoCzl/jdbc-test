package com.jdbctest.resultset;

import static org.junit.jupiter.api.Assertions.*;

import com.jdbctest.extension.JdbcContext;
import com.jdbctest.extension.JdbcTestExtension;
import com.jdbctest.extension.UseSqlScripts;
import com.jdbctest.extension.RequiresFeature;
import java.sql.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(JdbcTestExtension.class)
@Tag("integration")
@UseSqlScripts(ddl = "fetch_window_ddl.sql")
class FetchWindowTest {

    private void insertRows(Connection connection) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(JdbcContext.sql(
            "INSERT INTO {{fetch_window}} (id) VALUES (?)"))) {
            for (int id = 1; id <= 129; id++) {
                insert.setInt(1, id);
                insert.addBatch();
            }
            assertEquals(129, insert.executeBatch().length);
        }
    }

    private void assertSequential(ResultSet rows) throws SQLException {
        for (int expected = 1; expected <= 129; expected++) {
            assertTrue(rows.next(), "Missing row across fetch boundaries");
            assertEquals(expected, rows.getInt(1), "Duplicate or reordered row");
        }
        assertFalse(rows.next());
    }

    @Test
    @DisplayName("不同 FetchSize 跨批读取不丢行或重复")
    void testFetchWindowsPreserveAllRows(Connection connection) throws SQLException {
        connection.setAutoCommit(false);
        try {
            insertRows(connection);
            for (int size : new int[] {1, 16, 64}) {
                try (PreparedStatement sql = connection.prepareStatement(JdbcContext.sql(
                    "SELECT id FROM {{fetch_window}} ORDER BY id"))) {
                    sql.setFetchSize(size);
                    try (ResultSet rows = sql.executeQuery()) {
                        assertSequential(rows);
                    }
                }
            }
        } finally {
            connection.rollback();
        }
    }

    private void scrollWindows(Connection connection, boolean execute) throws SQLException {
        connection.setAutoCommit(false);
        try {
            insertRows(connection);
            for (int size : new int[] {1, 16, 64}) {
                try (PreparedStatement sql = connection.prepareStatement(JdbcContext.sql(
                        "SELECT id FROM {{fetch_window}} ORDER BY id"),
                    ResultSet.TYPE_SCROLL_INSENSITIVE,
                    ResultSet.CONCUR_READ_ONLY)) {
                    sql.setFetchSize(size);
                    ResultSet result;
                    if (execute) {
                        assertTrue(sql.execute());
                        result = sql.getResultSet();
                    } else {
                        result = sql.executeQuery();
                    }
                    try (ResultSet rows = result) {
                        assertNotNull(rows);
                        assertTrue(rows.last());
                        assertEquals(129, rows.getRow());
                        assertEquals(129, rows.getInt(1));
                        rows.beforeFirst();
                        assertSequential(rows);
                        rows.beforeFirst();
                        assertSequential(rows);
                    }
                }
            }
        } finally {
            connection.rollback();
        }
    }

    @Test
    @RequiresFeature("scrollable_result_set")
    @DisplayName("executeQuery 可滚动结果集 last 后跨 FetchSize 重读不丢行")
    void testScrollableFetchWindowsAfterLast(Connection connection) throws SQLException {
        scrollWindows(connection, false);
    }

    @Test
    @RequiresFeature("scrollable_result_set")
    @DisplayName("execute 可滚动结果集 last 后跨 FetchSize 重读不丢行")
    void testScrollableExecuteFetchWindowsAfterLast(Connection connection) throws SQLException {
        scrollWindows(connection, true);
    }

    @Test
    @DisplayName("PreparedStatement MaxRows 限制及清除后完整读取")
    void testPreparedMaxRowsRestoresFullWindow(Connection connection) throws SQLException {
        connection.setAutoCommit(false);
        try {
            insertRows(connection);
            try (PreparedStatement sql = connection.prepareStatement(JdbcContext.sql(
                "SELECT id FROM {{fetch_window}} ORDER BY id"))) {
                sql.setMaxRows(2);
                try (ResultSet rows = sql.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals(1, rows.getInt(1));
                    assertTrue(rows.next());
                    assertEquals(2, rows.getInt(1));
                    assertFalse(rows.next());
                }
                sql.setMaxRows(0);
                try (ResultSet rows = sql.executeQuery()) {
                    assertSequential(rows);
                }
            }
        } finally {
            connection.rollback();
        }
    }

    @Test
    @DisplayName("实际提交后的游标生命周期符合声明的 Holdability")
    void testDefaultHoldabilityAcrossCommit(Connection connection) throws SQLException {
        connection.setAutoCommit(false);
        try {
            insertRows(connection);
            int holdability = connection.getHoldability();
            try (Statement sql = connection.createStatement();
                ResultSet rows = sql.executeQuery(JdbcContext.sql(
                    "SELECT id FROM {{fetch_window}} ORDER BY id"))) {
                assertEquals(holdability, rows.getHoldability());
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1));
                connection.commit();
                if (holdability == ResultSet.CLOSE_CURSORS_AT_COMMIT) {
                    assertTrue(rows.isClosed());
                } else {
                    assertEquals(ResultSet.HOLD_CURSORS_OVER_COMMIT, holdability);
                    assertFalse(rows.isClosed());
                    for (int id = 2; id <= 129; id++) {
                        assertTrue(rows.next());
                        assertEquals(id, rows.getInt(1));
                    }
                    assertFalse(rows.next());
                }
            }
        } finally {
            connection.rollback();
            try (Statement sql = connection.createStatement()) {
                sql.executeUpdate(JdbcContext.sql(
                    "DELETE FROM {{fetch_window}} WHERE id >= 1 AND id <= 129"));
            }
            connection.commit();
        }
    }
}
