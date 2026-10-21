package com.jdbctest.datasource;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.jdbctest.extension.ExecutionPrerequisiteException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;

class XaPrerequisiteTest {

    @Test
    void disabledPreparedTransactionsAbortOnlyTheDependentContract() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet rows = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SELECT current_setting('max_prepared_transactions')"))
            .thenReturn(rows);
        when(rows.next()).thenReturn(true);
        var failure = assertThrows(ExecutionPrerequisiteException.class,
            () -> XaBehaviorTest.requirePreparedTransactions(connection, true));
        assertTrue(failure.getMessage().contains("max_prepared_transactions=0"));
        when(rows.getInt(1)).thenReturn(16);
        XaBehaviorTest.requirePreparedTransactions(connection, true);
        verify(rows, times(2)).close();
    }

    @Test
    void otherDialectsAreNotProbedWithPostgresqlSql() throws Exception {
        Connection connection = mock(Connection.class);
        XaBehaviorTest.requirePreparedTransactions(connection, false);
        verifyNoInteractions(connection);
    }
}
