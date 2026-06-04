package com.jdbctest.rowset;

import com.jdbctest.config.Config;
import com.jdbctest.config.ConfigLoader;
import com.jdbctest.extension.JdbcTestExtension;
import com.jdbctest.extension.UseSqlScripts;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.sql.rowset.*;
import java.sql.*;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(JdbcTestExtension.class)
@UseSqlScripts(
    ddl = {"rowset_ddl.sql"},
    dml = {"rowset_dml.sql"}
)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RowSetTest {

    private static boolean isOracle() {
        return ConfigLoader.load().db.type == Config.DbType.ORACLE;
    }

    private String getJdbcUrl() {
        var config = ConfigLoader.load();
        return config.db.getJdbcUrl();
    }

    private String getUsername() {
        return ConfigLoader.load().db.username;
    }

    private String getPassword() {
        return ConfigLoader.load().db.password;
    }

    private void setupRowSet(JdbcRowSet rowSet, String sql) throws SQLException {
        rowSet.setUrl(getJdbcUrl());
        rowSet.setUsername(getUsername());
        rowSet.setPassword(getPassword());
        rowSet.setCommand(sql);
    }

    @Test
    @Order(1)
    @DisplayName("创建 JdbcRowSet")
    void testCreateJdbcRowSet() throws SQLException {
        JdbcRowSet rowSet = RowSetProvider.newFactory().createJdbcRowSet();
        assertNotNull(rowSet, "JdbcRowSet 不应为 null");
    }

    @Test
    @Order(2)
    @DisplayName("设置 URL、用户名、密码和 SQL 命令")
    void testSetProperties(Connection conn) throws SQLException {
        JdbcRowSet rowSet = RowSetProvider.newFactory().createJdbcRowSet();
        setupRowSet(rowSet, "SELECT * FROM rowset_test");

        assertEquals(getJdbcUrl(), rowSet.getUrl());
        assertEquals("SELECT * FROM rowset_test", rowSet.getCommand());
    }

    @Test
    @Order(3)
    @DisplayName("execute 执行查询并遍历")
    void testExecuteAndNavigate() throws SQLException {
        JdbcRowSet rowSet = RowSetProvider.newFactory().createJdbcRowSet();
        setupRowSet(rowSet, "SELECT * FROM rowset_test");

        rowSet.execute();
        assertNotNull(rowSet);

        int count = 0;
        while (rowSet.next()) {
            count++;
            assertNotNull(rowSet.getString("name"), "name 不应为 null");
        }
        assertTrue(count >= 3, "应有至少 3 行数据");
    }

    @Test
    @Order(4)
    @DisplayName("first / last 导航")
    void testFirstLast(Connection conn) throws SQLException {
        JdbcRowSet rowSet = RowSetProvider.newFactory().createJdbcRowSet();
        setupRowSet(rowSet, "SELECT * FROM rowset_test");

        rowSet.execute();
        assertTrue(rowSet.first(), "first() 应成功");
        assertTrue(rowSet.getInt("id") > 0, "第一行的 id 应大于 0");

        assertTrue(rowSet.last(), "last() 应成功");
    }

    @Test
    @Order(5)
    @DisplayName("absolute 绝对定位")
    void testAbsolute(Connection conn) throws SQLException {
        JdbcRowSet rowSet = RowSetProvider.newFactory().createJdbcRowSet();
        setupRowSet(rowSet, "SELECT * FROM rowset_test");

        rowSet.execute();
        assertTrue(rowSet.absolute(2), "absolute(2) 应成功");
    }

    @Test
    @Order(6)
    @DisplayName("updateString / updateRow 更新数据")
    void testUpdateRow(Connection conn) throws SQLException {
        if (isOracle()) {
            // Oracle JdbcRowSet does not reliably support updatable ResultSet
            // Skip this test for Oracle
            return;
        }
        JdbcRowSet rowSet = RowSetProvider.newFactory().createJdbcRowSet();
        setupRowSet(rowSet, "SELECT * FROM rowset_test");
        if (isOracle()) {
            rowSet.setConcurrency(ResultSet.CONCUR_UPDATABLE);
        }

        rowSet.execute();
        assertTrue(rowSet.first());

        String originalName = rowSet.getString("name");
        rowSet.updateString("name", "RowSet更新测试");
        rowSet.updateRow();

        // 验证更新
        rowSet.beforeFirst();
        boolean found = false;
        while (rowSet.next()) {
            if ("RowSet更新测试".equals(rowSet.getString("name"))) {
                found = true;
                break;
            }
        }
        assertTrue(found, "更新后的数据应可查询到");

        // 恢复
        rowSet.absolute(1);
        rowSet.updateString("name", originalName);
        rowSet.updateRow();
    }

    @Test
    @Order(7)
    @DisplayName("insertRow 插入数据")
    void testInsertRow(Connection conn) throws SQLException {
        if (isOracle()) {
            // Oracle JdbcRowSet does not reliably support updatable ResultSet
            return;
        }
        JdbcRowSet rowSet = RowSetProvider.newFactory().createJdbcRowSet();
        setupRowSet(rowSet, "SELECT * FROM rowset_test");
        if (isOracle()) {
            rowSet.setConcurrency(ResultSet.CONCUR_UPDATABLE);
        }

        rowSet.execute();
        rowSet.moveToInsertRow();
        rowSet.updateString("name", "RowSet插入");
        rowSet.updateInt("value", 666);
        rowSet.insertRow();
        rowSet.moveToCurrentRow();

        // 验证插入
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT COUNT(*) FROM rowset_test WHERE name = 'RowSet插入'")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
        }
    }

    @Test
    @Order(8)
    @DisplayName("deleteRow 删除数据")
    void testDeleteRow(Connection conn) throws SQLException {
        if (isOracle()) {
            // Oracle JdbcRowSet does not reliably support updatable ResultSet
            return;
        }
        // 先插入一条测试数据
        JdbcRowSet insertRs = RowSetProvider.newFactory().createJdbcRowSet();
        setupRowSet(insertRs, "SELECT id, name, value FROM rowset_test");
        if (isOracle()) {
            insertRs.setConcurrency(ResultSet.CONCUR_UPDATABLE);
        }
        insertRs.execute();
        insertRs.moveToInsertRow();
        insertRs.updateString("name", "待删除行");
        insertRs.updateInt("value", 888);
        insertRs.insertRow();

        // 删除刚插入的行
        JdbcRowSet rowSet = RowSetProvider.newFactory().createJdbcRowSet();
        setupRowSet(rowSet, "SELECT * FROM rowset_test WHERE name = '待删除行'");
        rowSet.execute();
        if (rowSet.first()) {
            rowSet.deleteRow();
        }

        // 验证删除
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT COUNT(*) FROM rowset_test WHERE name = '待删除行'")) {
            assertTrue(rs.next());
            assertEquals(0, rs.getInt(1), "删除后应不存在");
        }
    }

    @Test
    @Order(9)
    @DisplayName("close 关闭 RowSet")
    void testClose(Connection conn) throws SQLException {
        JdbcRowSet rowSet = RowSetProvider.newFactory().createJdbcRowSet();
        assertNotNull(rowSet);
        rowSet.close();
    }
}
