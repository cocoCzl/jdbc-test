package com.jdbctest.databasemetadata;

import com.jdbctest.config.ConfigLoader;
import com.jdbctest.extension.JdbcTestExtension;
import com.jdbctest.extension.UseSqlScripts;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;

import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(JdbcTestExtension.class)
@UseSqlScripts(ddl = {"metadatatest_ddl.sql"}, dml = {"metadatatest_dml.sql"})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DatabaseMetaDataTest {

    private static boolean isOracle() {
        return ConfigLoader.load().db.isDialect("oracle");
    }

    private String getSchema(Connection conn) throws SQLException {
        if (isOracle()) {
            return conn.getSchema();
        }
        return null;
    }

    private String metadataTablePattern() {
        return isOracle() ? "METADATA_TEST" : "metadata_test";
    }

    @Test
    @Order(1)
    @DisplayName("getDatabaseProductName 获取数据库产品名")
    void testGetDatabaseProductName(Connection conn) throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();
        assertNotNull(meta.getDatabaseProductName());
        assertFalse(meta.getDatabaseProductName().isEmpty());
    }

    @Test
    @Order(2)
    @DisplayName("getDatabaseProductVersion 获取数据库版本")
    void testGetDatabaseProductVersion(Connection conn) throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();
        assertNotNull(meta.getDatabaseProductVersion());
        assertFalse(meta.getDatabaseProductVersion().isEmpty());
    }

    @Test
    @Order(3)
    @DisplayName("getDriverName 获取驱动名")
    void testGetDriverName(Connection conn) throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();
        assertNotNull(meta.getDriverName());
        assertFalse(meta.getDriverName().isEmpty());
    }

    @Test
    @Order(4)
    @DisplayName("getDriverVersion 获取驱动版本")
    void testGetDriverVersion(Connection conn) throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();
        assertNotNull(meta.getDriverVersion());
        assertFalse(meta.getDriverVersion().isEmpty());
    }

    @Test
    @Order(5)
    @DisplayName("getURL 获取连接 URL")
    void testGetURL(Connection conn) throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();
        String url = meta.getURL();
        assertNotNull(url);
        assertTrue(url.startsWith("jdbc:"), "URL 应以 jdbc: 开头");
    }

    @Test
    @Order(6)
    @DisplayName("getUserName 获取用户名")
    void testGetUserName(Connection conn) throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();
        assertNotNull(meta.getUserName());
    }

    @Test
    @Order(7)
    @DisplayName("getTables 获取表信息")
    void testGetTables(Connection conn) throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();
        // For Oracle, getTables with schema may not always find the table;
        // use direct query as fallback verification.
        if (isOracle()) {
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT table_name FROM user_tables WHERE table_name = 'METADATA_TEST'")) {
                assertTrue(rs.next(), "metadata_test 表应在 user_tables 中");
            }
        }
        String schema = getSchema(conn);
        try (ResultSet tables = meta.getTables(null, schema, metadataTablePattern(), new String[]{"TABLE"})) {
            assertNotNull(tables, "getTables 结果不应为 null");
            boolean found = false;
            while (tables.next()) {
                if ("metadata_test".equalsIgnoreCase(tables.getString("TABLE_NAME"))) {
                    found = true;
                    break;
                }
            }
            assertTrue(found, "getTables 应返回 metadata_test 表");
        }
    }

    @Test
    @Order(8)
    @DisplayName("getColumns 获取列信息")
    void testGetColumns(Connection conn) throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();
        // For Oracle, verify with direct query as fallback
        if (isOracle()) {
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT count(*) FROM user_tab_columns WHERE table_name = 'METADATA_TEST'")) {
                assertTrue(rs.next());
                assertTrue(rs.getInt(1) >= 3, "metadata_test 至少应有 3 列");
            }
        }
        String schema = getSchema(conn);
        try (ResultSet columns = meta.getColumns(null, schema, metadataTablePattern(), "%")) {
            assertNotNull(columns, "getColumns 结果不应为 null");
            boolean idFound = false;
            boolean nameFound = false;
            boolean valueFound = false;
            while (columns.next()) {
                String columnName = columns.getString("COLUMN_NAME");
                idFound |= "id".equalsIgnoreCase(columnName);
                nameFound |= "name".equalsIgnoreCase(columnName);
                valueFound |= "value".equalsIgnoreCase(columnName);
            }
            assertTrue(idFound, "getColumns 应返回 id 列");
            assertTrue(nameFound, "getColumns 应返回 name 列");
            assertTrue(valueFound, "getColumns 应返回 value 列");
        }
    }

    @Test
    @Order(9)
    @DisplayName("getPrimaryKeys 获取主键信息")
    void testGetPrimaryKeys(Connection conn) throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();
        // For Oracle, verify with direct query as fallback
        if (isOracle()) {
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT column_name FROM user_cons_columns cc "
                         + "JOIN user_constraints c ON cc.constraint_name = c.constraint_name "
                         + "WHERE c.table_name = 'METADATA_TEST' AND c.constraint_type = 'P'")) {
                assertTrue(rs.next(), "metadata_test 应有主键");
                assertTrue(rs.getString(1).equalsIgnoreCase("id"));
            }
        }
        String schema = getSchema(conn);
        try (ResultSet keys = meta.getPrimaryKeys(null, schema, metadataTablePattern())) {
            assertNotNull(keys, "getPrimaryKeys 结果不应为 null");
            boolean found = false;
            while (keys.next()) {
                if ("id".equalsIgnoreCase(keys.getString("COLUMN_NAME"))) {
                    found = true;
                    break;
                }
            }
            assertTrue(found, "getPrimaryKeys 应返回 id 主键");
        }
    }

    @Test
    @Order(10)
    @DisplayName("getSchemas 获取 Schema 信息")
    void testGetSchemas(Connection conn) throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();
        try (ResultSet schemas = meta.getSchemas()) {
            assertNotNull(schemas);
            // MySQL 将 databases 作为 schemas 返回，PG 返回 public 等
            var md = schemas.getMetaData();
            assertTrue(md.getColumnCount() > 0, "getSchemas 结果应包含列");
        }
    }

    @Test
    @Order(11)
    @DisplayName("getCatalogs 获取 Catalog 信息")
    void testGetCatalogs(Connection conn) throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();
        try (ResultSet catalogs = meta.getCatalogs()) {
            assertNotNull(catalogs);
        }
    }

    @Test
    @Order(12)
    @DisplayName("getJDBCMajorVersion 和 getJDBCMinorVersion")
    void testJDBCVersion(Connection conn) throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();
        assertTrue(meta.getJDBCMajorVersion() >= 4);
        assertTrue(meta.getJDBCMinorVersion() >= 0);
    }

    @Test
    @Order(13)
    @DisplayName("getDatabaseMajorVersion 和 getDatabaseMinorVersion")
    void testDatabaseVersion(Connection conn) throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();
        assertTrue(meta.getDatabaseMajorVersion() > 0);
    }

    @Test
    @Order(14)
    @DisplayName("getMaxConnections 获取最大连接数")
    void testGetMaxConnections(Connection conn) throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();
        int maxConns = meta.getMaxConnections();
        assertTrue(maxConns >= 0, "getMaxConnections 应返回非负值");
    }

    @Test
    @Order(15)
    @DisplayName("supportsTransactions 检查事务支持")
    void testSupportsTransactions(Connection conn) throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();
        assertTrue(meta.supportsTransactions(), "应支持事务");
    }

    @Test
    @Order(16)
    @DisplayName("supportsBatchUpdates 检查批处理支持")
    void testSupportsBatchUpdates(Connection conn) throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();
        assertTrue(meta.supportsBatchUpdates(), "应支持批处理");
    }

    @Test
    @Order(17)
    @DisplayName("supportsSavepoints 检查保存点支持")
    void testSupportsSavepoints(Connection conn) throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();
        assertTrue(meta.supportsSavepoints(), "应支持保存点");
    }

    @Test
    @Order(18)
    @DisplayName("getTypeInfo 获取类型信息")
    void testGetTypeInfo(Connection conn) throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();
        try (ResultSet types = meta.getTypeInfo()) {
            assertNotNull(types);
            int count = 0;
            while (types.next()) count++;
            assertTrue(count > 0, "应至少有一种类型");
        }
    }

    @Test
    @Order(19)
    @DisplayName("getProcedures 获取存储过程/函数信息")
    void testGetProcedures(Connection conn) throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();
        String schema = getSchema(conn);
        // Narrow search to avoid full-catalog scan on Oracle
        try (ResultSet procs = meta.getProcedures(null, schema, "M%")) {
            assertNotNull(procs);
        }
    }

    @Test
    @Order(20)
    @DisplayName("getSQLKeywords 获取 SQL 关键字")
    void testGetSQLKeywords(Connection conn) throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();
        String keywords = meta.getSQLKeywords();
        assertNotNull(keywords);
    }

    @Test
    @Order(21)
    @DisplayName("getIdentifierQuoteString 获取标识符引号")
    void testGetIdentifierQuoteString(Connection conn) throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();
        String quote = meta.getIdentifierQuoteString();
        assertNotNull(quote);
        assertFalse(quote.isEmpty(), "标识符引号不应为空");
        assertEquals(1, quote.length(), "引号应为单个字符");
    }

    @Test
    @Order(22)
    @DisplayName("getSearchStringEscape 获取转义字符")
    void testGetSearchStringEscape(Connection conn) throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();
        assertNotNull(meta.getSearchStringEscape());
    }

    @Test
    @Order(23)
    @DisplayName("supportsMultipleResultSets 检查多结果集支持")
    void testSupportsMultipleResultSets(Connection conn) throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();
        assertNotNull(meta);
        assertDoesNotThrow(meta::supportsMultipleResultSets, "supportsMultipleResultSets 应可正常调用");
    }
}
