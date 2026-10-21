package com.jdbctest.jdbctype;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jdbctest.extension.JdbcContracts;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;

class TypeMetadataRequirementTest {

    @Test
    void absentTypeRemainsFailedWithIndependentEvidence() throws Exception {
        var connection = mock(Connection.class);
        var metadata = mock(DatabaseMetaData.class);
        var rows = mock(ResultSet.class);
        var reporter = mock(TestReporter.class);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getTypeInfo()).thenReturn(rows);
        when(rows.next()).thenReturn(true, false);
        when(rows.getInt("DATA_TYPE")).thenReturn(Types.INTEGER);
        assertThrows(JdbcContracts.SuiteRequirementAssertionError.class,
            () -> new JdbcTypeCoverageTest().testRefTypeMetadata(connection, reporter));
        verify(metadata).getTypeInfo();
        verify(rows).close();
        verify(reporter).publishEntry("jdbc.type.required", Integer.toString(Types.REF));
        verify(reporter).publishEntry("jdbc.type.present", "false");
    }

    @Test
    void supportedTypePassesAfterReadingMetadata() throws Exception {
        var connection = mock(Connection.class);
        var metadata = mock(DatabaseMetaData.class);
        var rows = mock(ResultSet.class);
        var reporter = mock(TestReporter.class);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getTypeInfo()).thenReturn(rows);
        when(rows.next()).thenReturn(true, false);
        when(rows.getInt("DATA_TYPE")).thenReturn(Types.ROWID);
        new JdbcTypeCoverageTest().testRowIdTypeMetadata(connection, reporter);
        verify(reporter).publishEntry("jdbc.type.present", "true");
        verify(rows).close();
    }

    @Test
    void metadataAccessErrorsAreNotFeatureAbsence() throws Exception {
        var connection = mock(Connection.class);
        var metadata = mock(DatabaseMetaData.class);
        when(connection.getMetaData()).thenReturn(metadata);
        var failure = new SQLException("fixture permission failure", "42501");
        when(metadata.getTypeInfo()).thenThrow(failure);
        assertSame(failure, assertThrows(SQLException.class,
            () -> new JdbcTypeCoverageTest().testRefTypeMetadata(connection,
                mock(TestReporter.class))));
    }
}
