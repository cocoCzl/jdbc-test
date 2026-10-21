package com.jdbctest.resultset;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jdbctest.extension.JdbcContracts;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;

class RowIdContractTest {

    @Test
    void aNullValueProbeObservesOnlyTheExactGetterAndClosesResources() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet rows = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SELECT NULL")).thenReturn(rows);
        when(rows.next()).thenReturn(true);
        when(rows.getRowId(1)).thenThrow(new SQLFeatureNotSupportedException("getter", "0A000"));
        var failure = assertThrows(JdbcContracts.UnsupportedAllowedException.class,
            () -> RowIdBehaviorTest.observeGetRowId(connection, false));
        assertTrue(failure.getMessage().contains("ResultSet.getRowId"));
        verify(rows).close();
        verify(statement).close();
        doThrow(new SQLException("conversion", "22018")).when(rows).getRowId(1);
        assertThrows(SQLException.class,
            () -> RowIdBehaviorTest.observeGetRowId(connection, false));
    }

    @Test
    void oracleUsesDualAndAcceptingNullDoesNotAssertStorageSupport() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet rows = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SELECT NULL FROM DUAL")).thenReturn(rows);
        when(rows.next()).thenReturn(true);
        RowIdBehaviorTest.observeGetRowId(connection, true);
        verify(rows).getRowId(1);
        verify(connection, never()).prepareStatement(anyString());
    }
}
