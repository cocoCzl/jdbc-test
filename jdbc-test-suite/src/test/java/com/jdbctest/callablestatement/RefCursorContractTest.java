package com.jdbctest.callablestatement;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.jdbctest.extension.JdbcContracts;
import java.sql.CallableStatement;
import java.sql.JDBCType;
import java.sql.Types;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import org.junit.jupiter.api.Test;

class RefCursorContractTest {

    @Test
    void registrationDoesNotExecuteAScalarProcedureOrClaimCursorResults() throws Exception {
        CallableStatement sql = mock(CallableStatement.class);
        doThrow(new SQLFeatureNotSupportedException("SQLType registration", "0A000"))
            .when(sql).registerOutParameter(1, JDBCType.REF_CURSOR);
        var failure = assertThrows(JdbcContracts.UnsupportedAllowedException.class,
            () -> RefCursorTest.registerCursor(sql));
        assertTrue(failure.getMessage().contains("registerOutParameter(SQLType.REF_CURSOR)"));
        verify(sql, never()).execute();
        verify(sql, never()).getObject(1);
    }

    @Test
    void nativeCursorUsesTheOriginalIntegerOverloadWithoutRequiringTheSqlTypeOverload()
        throws Exception {
        CallableStatement sql = mock(CallableStatement.class);
        RefCursorTest.registerNativeCursor(sql);
        verify(sql).registerOutParameter(1, Types.REF_CURSOR);
        verify(sql, never()).registerOutParameter(1, JDBCType.REF_CURSOR);
    }

    @Test
    void acceptingRegistrationIsNotAFeaturePassAndOtherErrorsRemainUnclassified() throws Exception {
        CallableStatement sql = mock(CallableStatement.class);
        RefCursorTest.registerCursor(sql);
        verify(sql, never()).execute();
        doThrow(new SQLException("wrong parameter", "07009"))
            .when(sql).registerOutParameter(1, JDBCType.REF_CURSOR);
        assertThrows(SQLException.class, () -> RefCursorTest.registerCursor(sql));
    }
}
