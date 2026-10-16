package com.jdbctest.connection;

import static org.junit.jupiter.api.Assertions.*;

import com.jdbctest.extension.*;
import java.sql.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(JdbcTestExtension.class)
@Tag("integration")
@UseSqlScripts(ddl = "bulk_transaction_ddl.sql")
class BulkTransactionTest {

    private void write(Connection connection) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(JdbcContext.sql(
            "INSERT INTO {{bulk_transaction}} (id, value) VALUES (?, ?)"));
            PreparedStatement update = connection.prepareStatement(JdbcContext.sql(
                "UPDATE {{bulk_transaction}} SET value = ? WHERE id = ?"));
            PreparedStatement delete = connection.prepareStatement(JdbcContext.sql(
                "DELETE FROM {{bulk_transaction}} WHERE id = ?"))) {
            for (int id = 0; id < 300; id++) {
                insert.setInt(1, id);
                insert.setString(2, "初始-" + id);
                assertEquals(1, insert.executeUpdate());
                update.setString(1, "更新-" + id);
                update.setInt(2, id);
                assertEquals(1, update.executeUpdate());
                if (id % 3 == 0) {
                    delete.setInt(1, id);
                    assertEquals(1, delete.executeUpdate());
                }
            }
        }
    }

    private void assertRows(boolean committed) throws SQLException {
        try (Connection observer = JdbcContext.getConnection();
            Statement sql = observer.createStatement();
            ResultSet rows = sql.executeQuery(JdbcContext.sql(
                "SELECT id, value FROM {{bulk_transaction}} ORDER BY id"))) {
            if (committed) {
                for (int id = 0; id < 300; id++) {
                    if (id % 3 == 0) {
                        continue;
                    }
                    assertTrue(rows.next());
                    assertEquals(id, rows.getInt(1));
                    assertEquals("更新-" + id, rows.getString(2));
                }
            }
            assertFalse(rows.next());
        }
    }

    @Test
    @DisplayName("三百行连续增改删作为同一事务完整提交")
    void testBulkTransactionCommit(Connection connection) throws SQLException {
        connection.setAutoCommit(false);
        try {
            write(connection);
            connection.commit();
            assertRows(true);
        } finally {
            connection.rollback();
            try (Statement sql = connection.createStatement()) {
                sql.executeUpdate(
                    JdbcContext.sql("DELETE FROM {{bulk_transaction}} WHERE id >= 0 AND id < 300"));
            }
            connection.commit();
        }
    }

    @Test
    @DisplayName("三百行连续增改删作为同一事务完整回滚")
    void testBulkTransactionRollback(Connection connection) throws SQLException {
        connection.setAutoCommit(false);
        try {
            write(connection);
            connection.rollback();
            assertRows(false);
        } finally {
            connection.rollback();
        }
    }
}
