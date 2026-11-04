package com.jdbctest.advancedtype;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.jdbctest.extension.JdbcContracts;
import com.jdbctest.extension.JdbcTestExtension;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.SQLFeatureNotSupportedException;
import org.junit.jupiter.api.Test;

class ArrayContractTest {

    @Test
    void materializationAcceptsPrimitiveAndOracleNumericArraysWithoutLosingValues() throws Exception {
        Array array = mock(Array.class);
        for (Object representation : new Object[] {new int[] {1, 2, 3},
            new Integer[] {1, 2, 3}, new BigDecimal[] {BigDecimal.ONE,
                new BigDecimal("2"), new BigDecimal("3")}}) {
            when(array.getArray()).thenReturn(representation);
            assertArrayEquals(new int[] {1, 2, 3}, AdvancedTypeTest.integerElements(array));
        }
        when(array.getArray()).thenReturn(new BigDecimal[] {new BigDecimal("1.5")});
        assertThrows(AssertionError.class, () -> AdvancedTypeTest.integerElements(array));
        when(array.getArray()).thenReturn(new String[] {"1"});
        assertThrows(AssertionError.class, () -> AdvancedTypeTest.integerElements(array));
    }

    @Test
    void getterProbeIsIndependentOfNativeStorageAndNeverMakesANullValueAFeaturePass()
        throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet rows = mock(ResultSet.class);
        JdbcTestExtension.Fixtures fixtures = mock(JdbcTestExtension.Fixtures.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SELECT NULL")).thenReturn(rows);
        when(rows.next()).thenReturn(true);
        when(rows.getArray(1)).thenThrow(new SQLFeatureNotSupportedException("getter"));
        var failure = assertThrows(JdbcContracts.UnsupportedAllowedException.class,
            () -> AdvancedTypeTest.observeGetterThenPrepare(connection, fixtures, false));
        assertTrue(failure.getMessage().contains("ResultSet.getArray"));
        verifyNoInteractions(fixtures);
        doThrow(new SQLException("connection lost", "08006")).when(rows).getArray(1);
        assertThrows(SQLException.class,
            () -> AdvancedTypeTest.observeGetterThenPrepare(connection, fixtures, false));
        doThrow(new com.jdbctest.extension.ExecutionPrerequisiteException("no native storage", null))
            .when(fixtures).prepare();
        org.mockito.Mockito.doReturn(null).when(rows).getArray(1);
        when(statement.executeQuery("SELECT NULL FROM DUAL")).thenReturn(rows);
        assertThrows(com.jdbctest.extension.ExecutionPrerequisiteException.class,
            () -> AdvancedTypeTest.observeGetterThenPrepare(connection, fixtures, true));
        verify(fixtures).prepare();
    }

    @Test
    void unsupportedFactoryIsObservedBeforeAnyFixtureOrBinding() throws Exception {
        Connection connection = mock(Connection.class);
        JdbcTestExtension.Fixtures fixtures = mock(JdbcTestExtension.Fixtures.class);
        when(connection.createArrayOf(eq("INTEGER"), any(Object[].class)))
            .thenThrow(new SQLFeatureNotSupportedException("array factory", "0A000"));
        var failure = assertThrows(JdbcContracts.UnsupportedAllowedException.class,
            () -> AdvancedTypeTest.bindingArray(connection, fixtures, new Integer[] {10, 20, 30},
                false, "INTEGER"));
        assertTrue(failure.getMessage().contains("Connection.createArrayOf"));
        verifyNoInteractions(fixtures);
        verify(connection, never()).prepareStatement(anyString());
    }

    @Test
    void namedCollectionPreparesFixtureAndPreservesExactQualifiedCase() throws Exception {
        for (String schema : new String[] {"UPPER", "TEST_SCHEMA", "Mixed_Case"}) {
            Connection connection = mock(Connection.class);
            JdbcTestExtension.Fixtures fixtures = mock(JdbcTestExtension.Fixtures.class);
            Array array = mock(Array.class);
            Integer[] elements = {10, 20, 30};
            String typeName = "\"" + schema + "\".\"j0123456789abcdef0123_type\"";
            when(connection.createArrayOf(typeName, elements)).thenReturn(array);
            org.junit.jupiter.api.Assertions.assertSame(array,
                AdvancedTypeTest.bindingArray(connection, fixtures, elements, true, typeName));
            var order = org.mockito.Mockito.inOrder(fixtures, connection);
            order.verify(fixtures).prepare();
            order.verify(connection).createArrayOf(typeName, elements);
        }
    }

    @Test
    void namedCollectionStillPropagatesFactoryAndFixtureFailures() throws Exception {
        Connection connection = mock(Connection.class);
        JdbcTestExtension.Fixtures fixtures = mock(JdbcTestExtension.Fixtures.class);
        Integer[] elements = {10, 20, 30};
        var original = new SQLException("native type rejected", "22000", -3301);
        when(connection.createArrayOf("quoted type", elements)).thenThrow(original);
        org.junit.jupiter.api.Assertions.assertSame(original, assertThrows(SQLException.class,
            () -> AdvancedTypeTest.bindingArray(connection, fixtures, elements, true, "quoted type")));
        var missing = new com.jdbctest.extension.ExecutionPrerequisiteException("missing type", null);
        doThrow(missing).when(fixtures).prepare();
        org.junit.jupiter.api.Assertions.assertSame(missing,
            assertThrows(com.jdbctest.extension.ExecutionPrerequisiteException.class,
                () -> AdvancedTypeTest.bindingArray(connection, fixtures, elements, true, "quoted type")));
    }

    @Test
    void ordinaryFactoryErrorsAreNotReportedAsAllowedUnsupported() throws Exception {
        Connection connection = mock(Connection.class);
        when(connection.createArrayOf(eq("INTEGER"), any(Object[].class)))
            .thenThrow(new SQLException("invalid element type", "42704"));
        assertThrows(SQLException.class, () -> AdvancedTypeTest.bindingArray(connection,
            mock(JdbcTestExtension.Fixtures.class), new Integer[] {10, 20, 30}, false, "INTEGER"));
    }
}
