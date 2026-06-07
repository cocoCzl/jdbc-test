package com.jdbctest.wrapper;

import com.jdbctest.extension.JdbcContext;
import com.jdbctest.extension.JdbcTestExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Wrapper;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(JdbcTestExtension.class)
class WrapperTest {

    @Test
    @DisplayName("DataSource Wrapper 接口")
    void testDataSourceWrapper() throws SQLException {
        DataSource dataSource = JdbcContext.getDataSource();

        assertWrapperContract(dataSource, DataSource.class);
    }

    @Test
    @DisplayName("Connection Wrapper 接口")
    void testConnectionWrapper(Connection conn) throws SQLException {
        assertWrapperContract(conn, Connection.class);
    }

    @Test
    @DisplayName("Statement 和 ResultSet Wrapper 接口")
    void testStatementAndResultSetWrapper(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(selectOneSql(conn))) {
            assertWrapperContract(stmt, Statement.class);
            assertWrapperContract(rs, ResultSet.class);
        }
    }

    @Test
    @DisplayName("MetaData Wrapper 接口")
    void testMetaDataWrapper(Connection conn) throws SQLException {
        DatabaseMetaData databaseMetaData = conn.getMetaData();
        assertWrapperContract(databaseMetaData, DatabaseMetaData.class);

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(selectOneSql(conn))) {
            ResultSetMetaData resultSetMetaData = rs.getMetaData();
            assertWrapperContract(resultSetMetaData, ResultSetMetaData.class);
        }
    }

    private static void assertWrapperContract(Wrapper wrapper, Class<?> expectedType) throws SQLException {
        assertDoesNotThrow(() -> wrapper.isWrapperFor(expectedType), "isWrapperFor 有效 JDBC 类型不应抛异常");
        assertTrue(wrapper.isWrapperFor(expectedType), "应能识别有效 JDBC 包装类型");
        assertDoesNotThrow(() -> wrapper.unwrap(expectedType), "unwrap 有效 JDBC 类型不应抛异常");
        assertNotNull(wrapper.unwrap(expectedType), "unwrap 有效 JDBC 类型不应返回 null");
    }

    private static String selectOneSql(Connection conn) throws SQLException {
        String product = conn.getMetaData().getDatabaseProductName().toLowerCase();
        return product.contains("oracle") ? "SELECT 1 FROM DUAL" : "SELECT 1";
    }
}
