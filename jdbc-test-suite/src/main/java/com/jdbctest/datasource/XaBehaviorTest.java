package com.jdbctest.datasource;

import static org.junit.jupiter.api.Assertions.*;

import com.jdbctest.config.ConfigLoader;
import com.jdbctest.extension.*;
import java.nio.ByteBuffer;
import java.sql.*;
import java.util.*;
import javax.sql.XAConnection;
import javax.transaction.xa.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(JdbcTestExtension.class)
@Tag("integration")
@RequiresFeature("xa_data_source")
@UseSqlScripts(ddl = "xa_behavior_ddl.sql")
class XaBehaviorTest {

    static void requirePreparedTransactions(Connection connection, boolean postgresql)
        throws SQLException {
        if (!postgresql) {
            return;
        }
        try (Statement sql = connection.createStatement();
            ResultSet rows = sql.executeQuery("SELECT current_setting('max_prepared_transactions')")) {
            if (!rows.next()) {
                throw new ExecutionPrerequisiteException(
                    "POSTGRESQL_PREPARED_TRANSACTIONS_SETTING_UNAVAILABLE", null);
            }
            if (rows.getInt(1) == 0) {
                throw new ExecutionPrerequisiteException(
                    "POSTGRESQL_PREPARED_TRANSACTIONS_DISABLED: max_prepared_transactions=0; "
                        + "XA prepare was not invoked; no global setting was changed", null);
            }
        }
    }

    private static Xid newXid() {
        UUID id = UUID.randomUUID();
        byte[] global = ByteBuffer.allocate(16).putLong(id.getMostSignificantBits())
            .putLong(id.getLeastSignificantBits()).array();
        return new Xid() {
            public int getFormatId() {
                return 0x4a444243;
            }

            public byte[] getGlobalTransactionId() {
                return global.clone();
            }

            public byte[] getBranchQualifier() {
                return new byte[] {1};
            }
        };
    }

    private static final class Branch implements AutoCloseable {

        final XAConnection owner;
        final Connection connection;
        final XAResource resource;
        final Xid xid = newXid();
        int state; // 0 = idle, 1 = active, 2 = ended/prepared, 3 = resolved.

        Branch() throws Exception {
            owner = VendorDataSources.xa(ConfigLoader.load().db).getXAConnection();
            try {
                connection = JdbcContext.scopeConnection(owner.getConnection());
                resource = owner.getXAResource();
            } catch (Exception failure) {
                owner.close();
                throw failure;
            }
        }

        void insert(int id) throws Exception {
            resource.start(xid, XAResource.TMNOFLAGS);
            state = 1;
            try (PreparedStatement sql = connection.prepareStatement(JdbcContext.sql(
                "INSERT INTO {{xa_behavior}} (id, value) VALUES (?, ?)"))) {
                sql.setInt(1, id);
                sql.setInt(2, id * 10);
                assertEquals(1, sql.executeUpdate());
            }
            resource.end(xid, XAResource.TMSUCCESS);
            state = 2;
        }

        void prepare() throws XAException {
            assertEquals(XAResource.XA_OK, resource.prepare(xid),
                "A written branch must be prepared");
        }

        void commit(boolean onePhase) throws XAException {
            resource.commit(xid, onePhase);
            state = 3;
        }

        void rollback() throws XAException {
            resource.rollback(xid);
            state = 3;
        }

        @Override
        public void close() throws Exception {
            try {
                if (state == 1) {
                    resource.end(xid, XAResource.TMFAIL);
                    state = 2;
                }
                if (state == 2) {
                    rollback();
                }
            } finally {
                try {
                    connection.close();
                } finally {
                    owner.close();
                }
            }
        }
    }

    private void assertVisible(int id, boolean visible) throws SQLException {
        try (Connection connection = JdbcContext.getConnection();
            PreparedStatement sql = connection.prepareStatement(JdbcContext.sql(
                "SELECT value FROM {{xa_behavior}} WHERE id = ?"))) {
            sql.setInt(1, id);
            try (ResultSet rows = sql.executeQuery()) {
                assertEquals(visible, rows.next());
                if (visible) {
                    assertEquals(id * 10, rows.getInt(1));
                    assertFalse(rows.next());
                }
            }
        }
    }

    private void delete(int id) throws SQLException {
        try (Connection connection = JdbcContext.getConnection();
            PreparedStatement sql = connection.prepareStatement(JdbcContext.sql(
                "DELETE FROM {{xa_behavior}} WHERE id = ?"))) {
            connection.setAutoCommit(true);
            sql.setInt(1, id);
            sql.executeUpdate();
        }
    }

    @Test
    @DisplayName("XA 单阶段提交对新连接可见")
    void testXaOnePhaseCommit() throws Exception {
        try (Branch branch = new Branch()) {
            branch.insert(901);
            branch.commit(true);
            assertVisible(901, true);
        } finally {
            delete(901);
        }
    }

    @Test
    @DisplayName("XA prepare 后两阶段提交对新连接可见")
    void testXaTwoPhaseCommit(Connection connection) throws Exception {
        requirePreparedTransactions(connection, ConfigLoader.load().db.isDialect("postgresql"));
        try (Branch branch = new Branch()) {
            branch.insert(902);
            branch.prepare();
            branch.commit(false);
            assertVisible(902, true);
        } finally {
            delete(902);
        }
    }

    @Test
    @DisplayName("XA 分支回滚不保留写入")
    void testXaRollback() throws Exception {
        try (Branch branch = new Branch()) {
            branch.insert(903);
            branch.rollback();
            assertVisible(903, false);
        } finally {
            delete(903);
        }
    }

    @Test
    @DisplayName("XA 恢复扫描找到自己的 prepared 分支并回滚")
    void testXaPreparedRecoveryRollback(Connection connection) throws Exception {
        requirePreparedTransactions(connection, ConfigLoader.load().db.isDialect("postgresql"));
        try (Branch branch = new Branch()) {
            branch.insert(904);
            branch.prepare();
            XAConnection recovery = VendorDataSources.xa(ConfigLoader.load().db).getXAConnection();
            try {
                XAResource resource = recovery.getXAResource();
                List<Xid> found = new ArrayList<>();
                try {
                    Xid[] page = resource.recover(XAResource.TMSTARTRSCAN);
                    for (int pages = 0; page.length != 0; pages++) {
                        assertTrue(pages < 100, "Recovery scan must terminate");
                        found.addAll(Arrays.asList(page));
                        if (found.stream().anyMatch(item -> sameBranch(item, branch.xid))) {
                            break; // Stop once this test's branch is found; do not scan unrelated branches.
                        }
                        page = resource.recover(XAResource.TMNOFLAGS);
                    }
                } finally {
                    resource.recover(XAResource.TMENDRSCAN);
                }
                assertTrue(found.stream().anyMatch(item -> sameBranch(item, branch.xid)),
                    "Recovery must include this test's uniquely identified branch");
            } finally {
                recovery.close();
            }
            branch.rollback();
            assertVisible(904, false);
        } finally {
            delete(904);
        }
    }

    private static boolean sameBranch(Xid one, Xid two) {
        return one.getFormatId() == two.getFormatId()
            && Arrays.equals(one.getGlobalTransactionId(), two.getGlobalTransactionId())
            && Arrays.equals(one.getBranchQualifier(), two.getBranchQualifier());
    }
}
