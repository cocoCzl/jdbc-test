package com.jdbctest.blobclob;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jdbctest.extension.JdbcContracts;
import java.sql.Blob;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import javax.sql.rowset.serial.SerialClob;
import org.junit.jupiter.api.Test;

class LobContractAttributionTest {

    @Test
    void temporaryBindingObservesOptionalFactoryWithoutMaskingAccessErrors() throws Exception {
        var connection = mock(Connection.class);
        when(connection.createBlob()).thenThrow(new SQLFeatureNotSupportedException("factory"));
        assertThrows(JdbcContracts.UnsupportedAllowedException.class,
            () -> new LobPersistenceTest().testTemporaryLobBinding(connection));
        verify(connection, never()).createClob();
        var denied = new SQLException("access denied", "42501");
        doThrow(denied).when(connection).createBlob();
        assertEquals(denied, assertThrows(SQLException.class,
            () -> new LobPersistenceTest().testTemporaryLobBinding(connection)));
    }

    private Blob partialWrite(Connection connection) throws SQLException {
        var blob = mock(Blob.class);
        when(connection.createBlob()).thenReturn(blob);
        when(blob.setBytes(1, new byte[] {1, 2, 3, 4, 5})).thenReturn(5);
        when(blob.setBytes(2, new byte[] {8, 9, 10, 11}, 1, 2)).thenReturn(2);
        when(blob.length()).thenReturn(5L);
        when(blob.getBytes(1, 5)).thenReturn(new byte[] {1, 9, 10, 4, 5});
        return blob;
    }

    @Test
    void optionalSearchDoesNotDiscardTheEarlierExactMutationAssertions() throws Exception {
        var connection = mock(Connection.class);
        var blob = partialWrite(connection);
        when(blob.position(new byte[] {9, 10}, 1))
            .thenThrow(new SQLFeatureNotSupportedException("search unsupported"));
        assertThrows(JdbcContracts.UnsupportedAllowedException.class,
            () -> new BlobObjectTest().testBlobPartialWrite(connection));
        verify(blob).getBytes(1, 5);
        verify(blob).free();
        when(blob.getBytes(1, 5)).thenReturn(new byte[] {1, 8, 9, 4, 5});
        assertThrows(org.opentest4j.AssertionFailedError.class,
            () -> new BlobObjectTest().testBlobPartialWrite(connection));
    }

    @Test
    void ordinarySearchErrorsAreNotLegalUnsupportedResults() throws Exception {
        var connection = mock(Connection.class);
        var blob = partialWrite(connection);
        var error = new SQLException("connection lost", "08006");
        when(blob.position(new byte[] {9, 10}, 1)).thenThrow(error);
        assertEquals(error, assertThrows(SQLException.class,
            () -> new BlobObjectTest().testBlobPartialWrite(connection)));
        verify(blob).free();
    }

    @Test
    void jdkReferenceClobConfirmsSourceOffsetButDoesNotGuaranteeRecoveryAfterInvalidInput()
        throws Exception {
        var connection = mock(Connection.class);
        when(connection.createClob()).thenAnswer(ignored -> new SerialClob(new char[0]));
        var scenario = new ClobObjectTest();
        scenario.testClobPartialWrite(connection);
        var recovery = assertThrows(JdbcContracts.SuiteRequirementAssertionError.class,
            () -> scenario.testClobInvalidBounds(connection));
        org.junit.jupiter.api.Assertions.assertInstanceOf(SQLException.class, recovery.getCause());
        scenario.testClobAfterFree(connection);
    }
}
