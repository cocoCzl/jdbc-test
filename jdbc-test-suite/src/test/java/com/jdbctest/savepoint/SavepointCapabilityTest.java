package com.jdbctest.savepoint;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jdbctest.extension.JdbcContracts;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Savepoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;

class SavepointCapabilityTest {

    @Test
    void falseDeclarationDoesNotPreventTheActualCall() throws Exception {
        var connection = mock(Connection.class);
        when(connection.getMetaData()).thenReturn(mock(DatabaseMetaData.class));
        when(connection.setSavepoint()).thenReturn(mock(Savepoint.class));
        var reporter = mock(TestReporter.class);
        new SavepointTest().testSetSavepoint(connection, reporter);
        verify(connection).setSavepoint();
        verify(reporter).publishEntry("jdbc.capability.metadata.savepoint", "false");
        verify(reporter).publishEntry("jdbc.capability.behavior.savepoint", "true");
    }

    @Test
    void onlyTheScopedUnsupportedResponseIsPermitted() throws Exception {
        var connection = mock(Connection.class);
        when(connection.getMetaData()).thenReturn(mock(DatabaseMetaData.class));
        when(connection.setSavepoint()).thenThrow(new SQLFeatureNotSupportedException("fixture"));
        var reporter = mock(TestReporter.class);
        assertThrows(JdbcContracts.UnsupportedAllowedException.class,
            () -> new SavepointTest().testSetSavepoint(connection, reporter));
        verify(reporter).publishEntry("jdbc.capability.behavior.savepoint", "false");
        doThrow(new SQLException("fixture permission", "42501")).when(connection).setSavepoint();
        assertThrows(SQLException.class,
            () -> new SavepointTest().testSetSavepoint(connection, reporter));
        verify(reporter, never()).publishEntry("jdbc.capability.behavior.savepoint", "true");
    }
}
