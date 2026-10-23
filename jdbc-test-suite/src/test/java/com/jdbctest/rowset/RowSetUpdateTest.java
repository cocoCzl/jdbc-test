package com.jdbctest.rowset;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jdbctest.extension.JdbcContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.rowset.JdbcRowSet;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

class RowSetUpdateTest {

    @Test
    void verifiesDatabaseUpdateWhenTheInsensitiveCursorStillReturnsTheOldValue()
        throws SQLException {
        verifyUpdate("RowSet更新测试", false);
    }

    @Test
    void rejectsCorruptedStoredContentAndRestoresTheOriginalRow() throws SQLException {
        verifyUpdate("corrupted", true);
    }

    private void verifyUpdate(String storedName, boolean corrupted) throws SQLException {
        Connection connection = mock(Connection.class);
        Connection observer = mock(Connection.class);
        JdbcRowSet cursor = mock(JdbcRowSet.class);
        PreparedStatement query = mock(PreparedStatement.class);
        PreparedStatement restore = mock(PreparedStatement.class);
        ResultSet rows = mock(ResultSet.class);
        when(cursor.first()).thenReturn(true);
        when(cursor.getInt("id")).thenReturn(7);
        when(cursor.getString("name")).thenReturn("original");
        when(observer.prepareStatement(anyString())).thenReturn(query);
        when(query.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true, false);
        when(rows.getString(1)).thenReturn(storedName);
        when(connection.prepareStatement(anyString())).thenReturn(restore);
        when(restore.executeUpdate()).thenReturn(1);
        try (var context = mockStatic(JdbcContext.class)) {
            context.when(JdbcContext::getConnection).thenReturn(observer);
            context.when(() -> JdbcContext.sql(anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0));
            if (corrupted) {
                assertThrows(AssertionFailedError.class,
                    () -> RowSetTest.updateAndVerify(connection, cursor));
            } else {
                RowSetTest.updateAndVerify(connection, cursor);
            }
        }
        verify(cursor).updateRow();
        verify(query).setInt(1, 7);
        verify(restore).setString(1, "original");
        verify(restore).setInt(2, 7);
        verify(restore).executeUpdate();
        verify(observer).close();
    }
}
