package com.jdbctest.sqlxml;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jdbctest.extension.JdbcContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLXML;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

class SqlXmlLifecycleTest {

    @Test
    void readsAFreshValueAfterBindingAnUnreadableWrittenObject() throws SQLException {
        verifyRoundTrip("<test>hello</test>", false);
    }

    @Test
    void stillRejectsCorruptedContentAndReleasesBothObjects() throws SQLException {
        verifyRoundTrip("<test>corrupted</test>", true);
    }

    private void verifyRoundTrip(String content, boolean corrupted) throws SQLException {
        Connection connection = mock(Connection.class);
        PreparedStatement insert = mock(PreparedStatement.class);
        PreparedStatement select = mock(PreparedStatement.class);
        ResultSet rows = mock(ResultSet.class);
        SQLXML written = mock(SQLXML.class);
        SQLXML actual = mock(SQLXML.class);
        when(connection.createSQLXML()).thenReturn(written);
        when(connection.prepareStatement(anyString())).thenReturn(insert, select);
        when(insert.executeUpdate()).thenReturn(1);
        when(select.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true, false);
        when(rows.getSQLXML(1)).thenReturn(actual);
        when(written.getString()).thenThrow(new SQLException("not readable after write"));
        when(actual.getString()).thenReturn(content);
        try (var context = mockStatic(JdbcContext.class)) {
            context.when(() -> JdbcContext.sql(anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0));
            if (corrupted) {
                assertThrows(AssertionFailedError.class,
                    () -> new SQLXMLTest().testSetString(connection));
            } else {
                new SQLXMLTest().testSetString(connection);
            }
        }
        verify(insert).setSQLXML(2, written);
        verify(written, never()).getString();
        verify(written).free();
        verify(actual).free();
        verify(connection).rollback();
    }
}
