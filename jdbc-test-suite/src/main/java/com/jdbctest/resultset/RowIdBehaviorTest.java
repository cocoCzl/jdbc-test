package com.jdbctest.resultset;

import static org.junit.jupiter.api.Assertions.*;

import com.jdbctest.extension.JdbcContext;
import com.jdbctest.extension.JdbcContracts;
import com.jdbctest.extension.JdbcTestExtension;
import com.jdbctest.extension.RequiresFeature;
import com.jdbctest.extension.UseSqlScripts;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(JdbcTestExtension.class)
@Tag("integration")
@RequiresFeature("rowid_operations")
@UseSqlScripts(ddl = "rowid_behavior_ddl.sql", deferred = true)
class RowIdBehaviorTest {

    static void observeGetRowId(Connection connection, boolean oracle) throws SQLException {
        try (Statement sql = connection.createStatement();
            ResultSet rows = sql.executeQuery(oracle ? "SELECT NULL FROM DUAL" : "SELECT NULL")) {
            assertTrue(rows.next());
            assertNull(JdbcContracts.optional("ResultSet.getRowId", () -> rows.getRowId(1)),
                "SQL NULL must remain null; this probe does not establish ROWID storage support");
        }
    }

    private void prepare(Connection connection, JdbcTestExtension.Fixtures fixtures)
        throws SQLException {
        observeGetRowId(connection,
            com.jdbctest.config.ConfigLoader.load().db.isDialect("oracle"));
        fixtures.prepare();
    }

    private void bind(PreparedStatement sql, int index, RowId value) throws SQLException {
        JdbcContracts.optional("PreparedStatement.setRowId", () -> {
            sql.setRowId(index, value);
            return null;
        });
    }

    private RowId insertAndRead(Connection connection, int id) throws SQLException {
        try (PreparedStatement sql = connection.prepareStatement(JdbcContext.sql(
            "INSERT INTO {{rowid_behavior}} (id, value) VALUES (?, ?)"))) {
            sql.setInt(1, id);
            sql.setInt(2, id * 10);
            assertEquals(1, sql.executeUpdate());
        }
        try (PreparedStatement sql = connection.prepareStatement(JdbcContext.sql(
            "SELECT ROWID FROM {{rowid_behavior}} WHERE id = ?"))) {
            sql.setInt(1, id);
            try (ResultSet rows = sql.executeQuery()) {
                assertTrue(rows.next());
                RowId rowId = JdbcContracts.optional("ResultSet.getRowId", () -> rows.getRowId(1));
                assertNotNull(rowId);
                assertTrue(rowId.getBytes().length > 0);
                assertFalse(rows.next());
                return rowId;
            }
        }
    }

    @Test
    @DisplayName("getRowId/setRowId 精确定位同一条记录")
    void testRowIdRoundTrip(Connection connection, JdbcTestExtension.Fixtures fixtures)
        throws SQLException {
        prepare(connection, fixtures);
        connection.setAutoCommit(false);
        try {
            RowId rowId = insertAndRead(connection, 1);
            try (PreparedStatement sql = connection.prepareStatement(JdbcContext.sql(
                "SELECT id, ROWID FROM {{rowid_behavior}} WHERE ROWID = ?"))) {
                bind(sql, 1, rowId);
                try (ResultSet rows = sql.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals(1, rows.getInt(1));
                    RowId found = JdbcContracts.optional("ResultSet.getRowId", () -> rows.getRowId(2));
                    assertArrayEquals(rowId.getBytes(), found.getBytes());
                    assertFalse(rows.next());
                }
            }
        } finally {
            connection.rollback();
        }
    }

    @Test
    @DisplayName("RowId 更新后读取并删除对应记录")
    void testUpdateAndDeleteByRowId(Connection connection, JdbcTestExtension.Fixtures fixtures)
        throws SQLException {
        prepare(connection, fixtures);
        connection.setAutoCommit(false);
        try {
            RowId rowId = insertAndRead(connection, 2);
            try (PreparedStatement update = connection.prepareStatement(JdbcContext.sql(
                "UPDATE {{rowid_behavior}} SET value = 99 WHERE ROWID = ?"))) {
                bind(update, 1, rowId);
                assertEquals(1, update.executeUpdate());
            }
            try (Statement sql = connection.createStatement();
                ResultSet rows = sql.executeQuery(JdbcContext.sql(
                    "SELECT value FROM {{rowid_behavior}} WHERE id = 2"))) {
                assertTrue(rows.next());
                assertEquals(99, rows.getInt(1));
            }
            try (PreparedStatement delete = connection.prepareStatement(JdbcContext.sql(
                "DELETE FROM {{rowid_behavior}} WHERE ROWID = ?"))) {
                bind(delete, 1, rowId);
                assertEquals(1, delete.executeUpdate());
                assertEquals(0, delete.executeUpdate());
            }
        } finally {
            connection.rollback();
        }
    }

    @Test
    @DisplayName("批量 RowId 绑定不串行并可回滚")
    void testBatchRowIdBinding(Connection connection, JdbcTestExtension.Fixtures fixtures)
        throws SQLException {
        prepare(connection, fixtures);
        connection.setAutoCommit(false);
        try {
            List<RowId> ids = new ArrayList<>();
            for (int id = 10; id < 13; id++) {
                ids.add(insertAndRead(connection, id));
            }
            try (PreparedStatement sql = connection.prepareStatement(JdbcContext.sql(
                "UPDATE {{rowid_behavior}} SET value = ? WHERE ROWID = ?"))) {
                for (int i = 0; i < ids.size(); i++) {
                    sql.setInt(1, i);
                    bind(sql, 2, ids.get(i));
                    sql.addBatch();
                }
                int[] counts = sql.executeBatch();
                assertEquals(3, counts.length);
                for (int count : counts) {
                    assertTrue(count == 1 || count == Statement.SUCCESS_NO_INFO);
                }
            }
            try (Statement sql = connection.createStatement();
                ResultSet rows = sql.executeQuery(JdbcContext.sql(
                    "SELECT id, value FROM {{rowid_behavior}} ORDER BY id"))) {
                for (int i = 0; i < 3; i++) {
                    assertTrue(rows.next());
                    assertEquals(10 + i, rows.getInt(1));
                    assertEquals(i, rows.getInt(2));
                }
                assertFalse(rows.next());
            }
        } finally {
            connection.rollback();
        }
    }
}
