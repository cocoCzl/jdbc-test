package com.jdbctest.savepoint;

import com.jdbctest.config.Config;
import com.jdbctest.config.ConfigLoader;
import com.jdbctest.extension.JdbcTestExtension;
import com.jdbctest.extension.RequiresFeature;
import com.jdbctest.extension.UseSqlScripts;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;

import java.sql.*;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(JdbcTestExtension.class)
@RequiresFeature("savepoint")
@UseSqlScripts(
    ddl = {"savepoint_ddl.sql"},
    dml = {"savepoint_dml.sql"}
)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SavepointTest {

    private static boolean isOracle() {
        return ConfigLoader.load().db.isDialect("oracle");
    }

    @Test
    @Order(1)
    @DisplayName("setSavepoint 创建保存点")
    void testSetSavepoint(Connection conn) throws SQLException {
        conn.setAutoCommit(false);
        try {
            Savepoint sp = conn.setSavepoint();
            assertNotNull(sp, "setSavepoint 不应返回 null");
            conn.rollback(sp);
        } finally {
            conn.setAutoCommit(true);
        }
    }

    @Test
    @Order(2)
    @DisplayName("setSavepoint 命名保存点")
    void testSetSavepointWithName(Connection conn) throws SQLException {
        conn.setAutoCommit(false);
        try {
            Savepoint sp = conn.setSavepoint("my_sp");
            assertNotNull(sp);
            assertEquals("my_sp", sp.getSavepointName());
            if (!isOracle()) {
                conn.releaseSavepoint(sp);
            }
        } finally {
            conn.setAutoCommit(true);
        }
    }

    @Test
    @Order(3)
    @DisplayName("releaseSavepoint 释放保存点")
    void testReleaseSavepoint(Connection conn) throws SQLException {
        conn.setAutoCommit(false);
        try {
            Savepoint sp = conn.setSavepoint("rel_sp");
            if (isOracle()) {
                // Oracle JDBC does not support releaseSavepoint
                assertThrows(SQLFeatureNotSupportedException.class, () -> conn.releaseSavepoint(sp));
            } else {
                conn.releaseSavepoint(sp);
            }
        } finally {
            conn.setAutoCommit(true);
        }
    }

    @Test
    @Order(4)
    @DisplayName("rollback(Savepoint) 回滚到保存点")
    void testRollbackToSavepoint(Connection conn) throws SQLException {
        conn.setAutoCommit(false);
        try (Statement stmt = conn.createStatement()) {
            // 删除所有数据
            stmt.execute("DELETE FROM savepoint_test");

            Savepoint sp = conn.setSavepoint("after_delete");
            assertNotNull(sp);

            // 插入新数据
            stmt.execute("INSERT INTO savepoint_test (name, value) VALUES ('插入测试', 999)");

            // 回滚到保存点，插入应被撤销
            conn.rollback(sp);

            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM savepoint_test")) {
                rs.next();
                assertEquals(0, rs.getInt(1), "回滚后表应无数据");
            }
        } finally {
            conn.rollback(); // 回滚 DELETE，恢复数据
            conn.setAutoCommit(true);
        }
    }

    @Test
    @Order(5)
    @DisplayName("多个保存点的回滚")
    void testMultipleSavepoints(Connection conn) throws SQLException {
        conn.setAutoCommit(false);
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM savepoint_test WHERE name LIKE '多保存点%'");

            Savepoint sp1 = conn.setSavepoint("sp1");
            stmt.execute("INSERT INTO savepoint_test (name, value) VALUES ('多保存点A', 111)");

            Savepoint sp2 = conn.setSavepoint("sp2");
            stmt.execute("INSERT INTO savepoint_test (name, value) VALUES ('多保存点B', 222)");

            conn.rollback(sp2); // 只回滚 B 的插入

            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM savepoint_test WHERE name = '多保存点A'")) {
                rs.next();
                assertEquals(1, rs.getInt(1), "回滚到 sp2 后 A 应还在");
            }

            conn.rollback(sp1); // 回滚 A 的插入
        } finally {
            conn.rollback();
            conn.setAutoCommit(true);
        }
    }

    @Test
    @Order(6)
    @DisplayName("事务提交后保存点失效")
    void testSavepointAfterCommit(Connection conn) throws SQLException {
        conn.setAutoCommit(false);
        Savepoint sp = conn.setSavepoint("commit_sp");
        conn.commit();

        assertThrows(SQLException.class, () -> conn.rollback(sp),
                "提交后回滚到旧保存点应抛出异常");

        conn.setAutoCommit(true);
    }
}
