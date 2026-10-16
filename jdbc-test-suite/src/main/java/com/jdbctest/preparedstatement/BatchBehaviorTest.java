package com.jdbctest.preparedstatement;

import static org.junit.jupiter.api.Assertions.*;

import com.jdbctest.config.ConfigLoader;
import com.jdbctest.extension.JdbcContext;
import com.jdbctest.extension.JdbcTestExtension;
import com.jdbctest.extension.RequiresFeature;
import com.jdbctest.extension.UseSqlScripts;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(JdbcTestExtension.class)
@Tag("integration")
@UseSqlScripts(ddl = "batch_behavior_ddl.sql")
class BatchBehaviorTest {

    private String insertSql() {
        return JdbcContext.sql("INSERT INTO {{batch_behavior}} (name, value) VALUES (?, ?)");
    }

    private void assertCounts(int[] counts, int expected) {
        assertEquals(expected, counts.length);
        for (int count : counts) {
            assertTrue(count == 1 || count == Statement.SUCCESS_NO_INFO,
                "Each successful batch item must insert one row or report SUCCESS_NO_INFO");
        }
    }

    private void assertStoredRows(Connection connection, Map<String, Integer> expected)
        throws SQLException {
        Map<String, Integer> actual = new HashMap<>();
        try (Statement sql = connection.createStatement();
            ResultSet rows = sql.executeQuery(JdbcContext.sql(
                "SELECT name, value FROM {{batch_behavior}}"))) {
            while (rows.next()) {
                String name = rows.getString(1);
                int value = rows.getInt(2);
                assertFalse(actual.containsKey(name), "Unexpected duplicate batch row");
                actual.put(name, rows.wasNull() ? null : value);
            }
        }
        assertEquals(expected, actual);
    }

    @Test
    @DisplayName("批处理保存每次绑定值并正确切换 NULL")
    void testBatchSnapshotsParameters(Connection connection) throws SQLException {
        connection.setAutoCommit(false);
        try (PreparedStatement sql = connection.prepareStatement(insertSql())) {
            sql.setString(1, "first");
            sql.setInt(2, 11);
            sql.addBatch();
            sql.setString(1, "second");
            sql.setNull(2, Types.INTEGER);
            sql.addBatch();
            sql.setString(1, "third");
            sql.setInt(2, 33);
            sql.addBatch();
            assertCounts(sql.executeBatch(), 3);
            Map<String, Integer> expected = new HashMap<>();
            expected.put("first", 11);
            expected.put("second", null);
            expected.put("third", 33);
            assertStoredRows(connection, expected);
        } finally {
            connection.rollback();
        }
    }

    @Test
    @DisplayName("清空批处理后只执行新加入的项")
    void testClearBatchDiscardsQueuedRows(Connection connection) throws SQLException {
        connection.setAutoCommit(false);
        try (PreparedStatement sql = connection.prepareStatement(insertSql())) {
            sql.setString(1, "discarded");
            sql.setInt(2, 1);
            sql.addBatch();
            sql.clearBatch();
            sql.setString(1, "retained");
            sql.setInt(2, 2);
            sql.addBatch();
            assertCounts(sql.executeBatch(), 1);
            assertStoredRows(connection, Map.of("retained", 2));
        } finally {
            connection.rollback();
        }
    }

    @Test
    @DisplayName("批处理中间项失败后可回滚全部写入")
    void testMiddleBatchFailureAndRollback(Connection connection) throws SQLException {
        connection.setAutoCommit(false);
        try (PreparedStatement sql = connection.prepareStatement(insertSql())) {
            for (String name : List.of("duplicate", "duplicate", "last")) {
                sql.setString(1, name);
                sql.setInt(2, 1);
                sql.addBatch();
            }
            BatchUpdateException failure = assertThrows(BatchUpdateException.class,
                sql::executeBatch);
            int[] counts = failure.getUpdateCounts();
            assertNotNull(counts);
            assertTrue(counts.length <= 3);
            for (int count : counts) {
                assertTrue(count == 1 || count == Statement.SUCCESS_NO_INFO
                    || count == Statement.EXECUTE_FAILED);
            }
            connection.rollback();
            assertStoredRows(connection, Map.of());
            // A failed batch must not poison subsequent transactions on the same connection.
            sql.clearBatch();
            sql.setString(1, "recovered");
            sql.setInt(2, 7);
            assertEquals(1, sql.executeUpdate());
            assertStoredRows(connection, Map.of("recovered", 7));
        } finally {
            connection.rollback();
        }
    }

    @Test
    @RequiresFeature({"generated_keys", "batch_generated_keys"})
    @DisplayName("按列名获取批量返回键并核对记录")
    void testBatchKeysByColumnName(Connection connection) throws SQLException {
        String column = ConfigLoader.load().db.isDialect("oracle") ? "ID" : "id";
        try (PreparedStatement sql = connection.prepareStatement(insertSql(),
            new String[] {column})) {
            assertBatchKeys(connection, sql);
        }
    }

    @Test
    @RequiresFeature({"generated_keys", "batch_generated_keys", "generated_keys_by_index"})
    @DisplayName("按列下标获取批量返回键并核对记录")
    void testBatchKeysByColumnIndex(Connection connection) throws SQLException {
        try (PreparedStatement sql = connection.prepareStatement(insertSql(), new int[] {1})) {
            assertBatchKeys(connection, sql);
        }
    }

    @Test
    @RequiresFeature({"generated_keys", "batch_generated_keys"})
    @DisplayName("自动获取批量返回键并核对记录")
    void testBatchKeysAutomatic(Connection connection) throws SQLException {
        try (PreparedStatement sql = connection.prepareStatement(insertSql(),
            Statement.RETURN_GENERATED_KEYS)) {
            assertBatchKeys(connection, sql);
        }
    }

    private void assertBatchKeys(Connection connection, PreparedStatement sql) throws SQLException {
        connection.setAutoCommit(false);
        try {
            for (int i = 0; i < 3; i++) {
                sql.setString(1, "key-" + i);
                sql.setInt(2, i);
                sql.addBatch();
            }
            assertCounts(sql.executeBatch(), 3);
            Set<Long> keys = new HashSet<>();
            Set<Integer> values = new HashSet<>();
            try (ResultSet rows = sql.getGeneratedKeys();
                PreparedStatement lookup = connection.prepareStatement(JdbcContext.sql(
                    "SELECT name, value FROM {{batch_behavior}} WHERE id = ?"))) {
                while (rows.next()) {
                    long key = rows.getLong(1);
                    assertFalse(rows.wasNull());
                    assertTrue(keys.add(key), "Generated keys must be unique");
                    lookup.setLong(1, key);
                    try (ResultSet stored = lookup.executeQuery()) {
                        assertTrue(stored.next(), "Returned key must identify an inserted row");
                        int value = stored.getInt(2);
                        assertEquals("key-" + value, stored.getString(1));
                        assertTrue(values.add(value));
                        assertFalse(stored.next());
                    }
                }
            }
            assertEquals(3, keys.size());
            assertEquals(Set.of(0, 1, 2), values);
        } finally {
            connection.rollback();
        }
    }
}
