package com.jdbctest.databasemetadata;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import org.opentest4j.AssertionFailedError;

class MetadataCapabilityTest {

    @Test
    void falseIsAValidMetadataDeclarationAndIsRetained() throws Exception {
        var connection = mock(Connection.class);
        var metadata = mock(DatabaseMetaData.class);
        var reporter = mock(TestReporter.class);
        when(connection.getMetaData()).thenReturn(metadata);
        var scenario = new DatabaseMetaDataTest();
        scenario.testSupportsTransactions(connection, reporter);
        scenario.testSupportsBatchUpdates(connection, reporter);
        scenario.testSupportsSavepoints(connection, reporter);
        verify(reporter).publishEntry("jdbc.capability.metadata.transactions", "false");
        verify(reporter).publishEntry("jdbc.capability.metadata.batch_updates", "false");
        verify(reporter).publishEntry("jdbc.capability.metadata.savepoint", "false");
    }

    @Test
    void inconsistentTransactionIsolationStillFailsItsSpecificContract() throws Exception {
        var connection = mock(Connection.class);
        var metadata = mock(DatabaseMetaData.class);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDefaultTransactionIsolation())
            .thenReturn(Connection.TRANSACTION_READ_COMMITTED);
        assertThrows(AssertionFailedError.class, () -> new DatabaseMetaDataTest()
            .testSupportsTransactions(connection, mock(TestReporter.class)));
    }

    @Test
    void metadataAccessErrorsAreNotConvertedToUnsupported() throws Exception {
        var connection = mock(Connection.class);
        var metadata = mock(DatabaseMetaData.class);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.supportsSavepoints())
            .thenThrow(new SQLException("fixture permission error", "42501"));
        assertThrows(SQLException.class, () -> new DatabaseMetaDataTest()
            .testSupportsSavepoints(connection, mock(TestReporter.class)));
    }
}
