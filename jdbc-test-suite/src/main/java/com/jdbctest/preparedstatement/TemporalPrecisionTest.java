package com.jdbctest.preparedstatement;

import static org.junit.jupiter.api.Assertions.*;

import com.jdbctest.extension.JdbcContext;
import com.jdbctest.extension.JdbcTestExtension;
import com.jdbctest.extension.UseSqlScripts;
import java.sql.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(JdbcTestExtension.class)
@Tag("integration")
@UseSqlScripts(ddl = "temporal_precision_ddl.sql")
class TemporalPrecisionTest {

    private void roundTrip(Connection connection, String column, Timestamp expected)
        throws SQLException {
        connection.setAutoCommit(false);
        try (PreparedStatement insert = connection.prepareStatement(JdbcContext.sql(
            "INSERT INTO {{temporal_precision}} (id, " + column + ") VALUES (1, ?)"))) {
            if (expected == null) {
                insert.setNull(1, Types.TIMESTAMP);
            } else {
                insert.setTimestamp(1, expected);
            }
            assertEquals(1, insert.executeUpdate());
            try (Statement sql = connection.createStatement();
                ResultSet rows = sql.executeQuery(JdbcContext.sql(
                    "SELECT " + column + " FROM {{temporal_precision}} WHERE id = 1"))) {
                assertTrue(rows.next());
                assertEquals(expected, rows.getTimestamp(1));
                assertEquals(expected == null, rows.wasNull());
                assertFalse(rows.next());
            }
        } finally {
            connection.rollback();
        }
    }

    @Test
    @DisplayName("毫秒时间戳完整往返")
    void testMillisecondPrecision(Connection connection) throws SQLException {
        roundTrip(connection, "ts3", Timestamp.valueOf("2024-02-29 23:59:59.123"));
    }

    @Test
    @DisplayName("微秒时间戳完整往返")
    void testMicrosecondPrecision(Connection connection) throws SQLException {
        roundTrip(connection, "ts6", Timestamp.valueOf("2024-02-29 23:59:59.123456"));
    }

    @Test
    @DisplayName("NULL 时间戳和 wasNull 保持一致")
    void testNullTimestamp(Connection connection) throws SQLException {
        roundTrip(connection, "ts6", null);
    }

    @Test
    @DisplayName("时间戳边界逐条验证无缺行")
    void testTimestampBoundaries(Connection connection) throws SQLException {
        // Portable dates: unlike the source's 1000/9999 bounds, also valid for MySQL TIMESTAMP.
        for (String value : new String[] {"2000-01-01 00:00:00.000000",
            "2030-12-31 23:59:59.999999"}) {
            roundTrip(connection, "ts6", Timestamp.valueOf(value));
        }
    }
}
