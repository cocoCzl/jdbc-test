package com.jdbctest.extension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import org.junit.jupiter.api.Test;

class JdbcContractsTest {

    @Test
    void onlyExplicitUnsupportedExceptionForTheOptionalCallConforms() {
        var unsupported = new SQLFeatureNotSupportedException("fixture unsupported", "0A000", 7);
        var result = assertThrows(JdbcContracts.UnsupportedAllowedException.class,
            () -> JdbcContracts.optional("Connection.createBlob", () -> { throw unsupported; }));
        assertEquals(unsupported, result.getCause());
        var ordinary = new SQLException("fixture permission failure", "42501");
        assertEquals(ordinary, assertThrows(SQLException.class,
            () -> JdbcContracts.optional("Connection.createBlob", () -> { throw ordinary; })));
        var misleading = new SQLException("JDBC permits unsupported API: Connection.createBlob",
            "0A000", 99);
        assertEquals(misleading, assertThrows(SQLException.class,
            () -> JdbcContracts.optional("Connection.createBlob", () -> { throw misleading; })));
        assertThrows(IllegalArgumentException.class,
            () -> JdbcContracts.optional("Connection.commit", () -> { throw unsupported; }));
    }
}
