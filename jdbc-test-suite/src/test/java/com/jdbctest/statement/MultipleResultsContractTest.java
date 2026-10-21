package com.jdbctest.statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jdbctest.extension.JdbcContext;
import com.jdbctest.extension.JdbcContracts;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class MultipleResultsContractTest {

    private void sequence(int updateCount, int storedValue) throws Exception {
        var connection = mock(Connection.class);
        var call = mock(CallableStatement.class);
        var check = mock(Statement.class);
        var first = mock(ResultSet.class);
        var second = mock(ResultSet.class);
        var stored = mock(ResultSet.class);
        when(connection.prepareCall(anyString())).thenReturn(call);
        when(connection.createStatement()).thenReturn(check);
        when(check.executeQuery(anyString())).thenReturn(stored);
        when(stored.next()).thenReturn(true, false);
        when(stored.getInt(1)).thenReturn(storedValue);
        when(first.next()).thenReturn(true, false);
        when(second.next()).thenReturn(true, false);
        when(first.getInt(1)).thenReturn(11);
        when(second.getInt(1)).thenReturn(22);
        when(first.isClosed()).thenReturn(true);
        when(second.isClosed()).thenReturn(true);
        when(call.execute()).thenReturn(true);
        var cursor = new AtomicInteger();
        when(call.getResultSet()).thenAnswer(ignored -> cursor.get() == 0 ? first : second);
        when(call.getMoreResults(Statement.CLOSE_CURRENT_RESULT))
            .thenAnswer(ignored -> cursor.incrementAndGet() == 1);
        when(call.getUpdateCount()).thenAnswer(ignored -> cursor.get() == 2 ? updateCount : -1);
        var observations = new HashMap<String, String>();
        try (var jdbc = mockStatic(JdbcContext.class)) {
            jdbc.when(() -> JdbcContext.sql(anyString())).thenAnswer(callSql -> callSql.getArgument(0));
            try {
                new MultipleResultsTest().testResultSequence(connection, observations::putAll);
            } finally {
                verify(connection).rollback();
                verify(stored).getInt(1);
                assertEquals("[" + updateCount + "]", observations.get("jdbc.results.updateCounts"));
            }
        }
    }

    @Test
    void procedureWithoutAnInternalUpdateCountStillChecksBothResultsAndStoredEffect() {
        assertThrows(JdbcContracts.SuiteRequirementAssertionError.class, () -> sequence(0, 1));
    }

    @Test
    void anExposedOneRowCountAndCorrectStoredEffectMeetTheSuiteRequirement() throws Exception {
        sequence(1, 1);
    }

    @Test
    void aWrongStoredEffectRemainsAnAssertionFailure() {
        var failure = assertThrows(AssertionError.class, () -> sequence(0, 2));
        assertEquals(org.opentest4j.AssertionFailedError.class, failure.getClass());
    }
}
