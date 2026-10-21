package com.jdbctest.extension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import org.junit.jupiter.api.Test;

class JdbcContractsTest {

    @Test
    void reviewedLobMethodsOnlyAllowTheExactUnsupportedExceptionAtThatCall() throws Exception {
        for (String api : java.util.List.of("Clob.setCharacterStream", "Blob.position(byte[],long)")) {
            var unsupported = new SQLFeatureNotSupportedException("reviewed method");
            assertEquals(unsupported, assertThrows(JdbcContracts.UnsupportedAllowedException.class,
                () -> JdbcContracts.optional(api, () -> { throw unsupported; })).getCause());
            var access = new SQLException("permission failure", "42501");
            assertEquals(access, assertThrows(SQLException.class,
                () -> JdbcContracts.optional(api, () -> { throw access; })));
            assertEquals(7, JdbcContracts.optional(api, () -> 7));
        }
        assertThrows(IllegalArgumentException.class,
            () -> JdbcContracts.optional("ResultSet.getHoldability", () -> 1));
    }

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
