package com.jdbctest.datasource;

import static org.junit.jupiter.api.Assertions.*;

import com.jdbctest.config.ConfigLoader;
import com.jdbctest.extension.*;
import java.sql.Connection;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(JdbcTestExtension.class)
@Tag("integration")
@RequiresFeature("connection_pool")
class PooledConnectionBehaviorTest {

    @Test
    @DisplayName("PooledConnection 逻辑关闭发送事件且物理连接可再次借用")
    void testPooledLogicalCloseEventAndReuse() throws Exception {
        PooledConnection pooled = VendorDataSources.pooled(ConfigLoader.load().db)
            .getPooledConnection();
        AtomicInteger closed = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        ConnectionEventListener listener = new ConnectionEventListener() {
            public void connectionClosed(ConnectionEvent event) {
                assertSame(pooled, event.getSource());
                closed.incrementAndGet();
            }

            public void connectionErrorOccurred(ConnectionEvent event) {
                errors.incrementAndGet();
            }
        };
        pooled.addConnectionEventListener(listener);
        try {
            try (Connection first = JdbcContext.scopeConnection(pooled.getConnection())) {
                assertTrue(first.isValid(5));
            }
            assertEquals(1, closed.get());
            assertEquals(0, errors.get());
            pooled.removeConnectionEventListener(listener);
            try (Connection second = JdbcContext.scopeConnection(pooled.getConnection())) {
                assertTrue(second.isValid(5));
            }
            assertEquals(1, closed.get(),
                "Removed listeners must not receive further logical-close events");
        } finally {
            pooled.removeConnectionEventListener(listener);
            pooled.close();
        }
    }

    @Test
    @DisplayName("PooledConnection 新逻辑借用使旧句柄失效")
    void testPooledReplacementInvalidatesPreviousHandle() throws Exception {
        PooledConnection pooled = VendorDataSources.pooled(ConfigLoader.load().db)
            .getPooledConnection();
        try {
            try (Connection first = JdbcContext.scopeConnection(pooled.getConnection());
                Connection second = JdbcContext.scopeConnection(pooled.getConnection())) {
                assertTrue(first.isClosed());
                assertFalse(second.isClosed());
                assertTrue(second.isValid(5));
            }
        } finally {
            pooled.close();
        }
    }
}
