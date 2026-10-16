package com.jdbctest.extension;

import java.util.Hashtable;
import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NameNotFoundException;
import javax.naming.NamingException;
import javax.naming.spi.InitialContextFactory;

/**
 * JdbcRowSet's standard DataSource path gives it the same scoped external-driver connections as the
 * suite.
 */
public final class RowSetContextFactory implements InitialContextFactory {

    @Override
    public Context getInitialContext(Hashtable<?, ?> environment) throws NamingException {
        return new InitialContext(true) {
            @Override
            public Object lookup(String name) throws NamingException {
                if (!"jdbc-test/run".equals(name) || JdbcContext.getDataSource() == null) {
                    throw new NameNotFoundException(name);
                }
                return JdbcContext.getDataSource();
            }

            @Override
            public void close() {
            }
        };
    }
}
