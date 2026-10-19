package com.jdbctest.connection;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;

class HoldabilityDeclarationTest {

    @Test
    void inconsistentDeclarationIsRecordedAfterActuallyExercisingTheApi() throws Exception {
        var connection = mock(Connection.class);
        var metadata = mock(DatabaseMetaData.class);
        var reporter = mock(TestReporter.class);
        when(connection.getMetaData()).thenReturn(metadata);
        when(connection.getHoldability()).thenReturn(ResultSet.CLOSE_CURSORS_AT_COMMIT);
        when(metadata.supportsResultSetHoldability(ResultSet.CLOSE_CURSORS_AT_COMMIT)).thenReturn(false);
        assertThrows(org.opentest4j.AssertionFailedError.class,
            () -> new ConnectionTest().testHoldability(connection, reporter));
        verify(connection).setHoldability(ResultSet.CLOSE_CURSORS_AT_COMMIT);
        verify(reporter).publishEntry("supportsResultSetHoldability", "false");
    }
}
