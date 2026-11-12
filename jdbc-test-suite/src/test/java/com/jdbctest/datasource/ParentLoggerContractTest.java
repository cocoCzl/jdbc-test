package com.jdbctest.datasource;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.jdbctest.extension.JdbcContracts;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

class ParentLoggerContractTest {

    @Test
    void nullStillFailsButADeclared40BaselineDoesNotEstablish41Applicability() throws Exception {
        var metadata = mock(DatabaseMetaData.class);
        var connection = mock(Connection.class);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getJDBCMajorVersion()).thenReturn(4);
        when(metadata.getJDBCMinorVersion()).thenReturn(0);
        var failure = assertThrows(JdbcContracts.ContractApplicabilityAssertionError.class,
            () -> DataSourceTest.assertParentLogger(null, connection));
        assertInstanceOf(org.opentest4j.AssertionFailedError.class, failure.getCause());
        assertTrue(failure.getMessage().contains("4.0"));
        when(metadata.getJDBCMinorVersion()).thenReturn(1);
        assertThrows(org.opentest4j.AssertionFailedError.class,
            () -> DataSourceTest.assertParentLogger(null, connection));
        when(metadata.getJDBCMajorVersion()).thenReturn(5);
        assertThrows(org.opentest4j.AssertionFailedError.class,
            () -> DataSourceTest.assertParentLogger(null, connection));
    }

    @Test
    void missingVersionPreservesBothFailuresAndAValidLoggerNeedsNoVersionExemption()
        throws Exception {
        var metadata = mock(DatabaseMetaData.class);
        var connection = mock(Connection.class);
        DataSourceTest.assertParentLogger(Logger.getLogger("fixture"), connection);
        verifyNoInteractions(connection, metadata);
        when(connection.getMetaData()).thenReturn(metadata);
        var missing = new SQLException("version unavailable", "HY000", 7);
        when(metadata.getJDBCMajorVersion()).thenThrow(missing);
        var failure = assertThrows(JdbcContracts.ContractApplicabilityAssertionError.class,
            () -> DataSourceTest.assertParentLogger(null, connection));
        assertSame(missing, failure.getCause().getSuppressed()[0]);
        when(connection.getMetaData()).thenThrow(missing);
        var failedMetadata = assertThrows(JdbcContracts.ContractApplicabilityAssertionError.class,
            () -> DataSourceTest.assertParentLogger(null, connection));
        assertSame(missing, failedMetadata.getCause().getSuppressed()[0]);
    }
}
