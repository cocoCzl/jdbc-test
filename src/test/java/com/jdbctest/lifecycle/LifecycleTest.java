package com.jdbctest.lifecycle;

import com.jdbctest.config.ConfigLoader;
import com.jdbctest.extension.JdbcTestExtension;
import com.jdbctest.extension.RequiresFeature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** JDBC resource ownership and invalid-state rules that do not require DDL. */
@ExtendWith(JdbcTestExtension.class)
class LifecycleTest {

    @Test
    void testClosedConnectionRejectsOperations(Connection connection) throws SQLException {
        connection.close();
        assertTrue(connection.isClosed(), "close() 后连接应处于关闭状态");
        assertDoesNotThrow(connection::close, "Connection.close() 应可重复调用");
        assertThrows(SQLException.class, connection::createStatement,
                "关闭后的连接不得再创建 Statement");
    }

    @Test
    void testStatementCloseClosesCurrentResultSet(Connection connection) throws SQLException {
        Statement statement = connection.createStatement();
        ResultSet resultSet = statement.executeQuery(ConfigLoader.load().db.isDialect("oracle")
                ? "SELECT 1 FROM DUAL" : "SELECT 1");
        statement.close();
        assertTrue(resultSet.isClosed(), "关闭 Statement 应关闭其当前 ResultSet");
    }

    @Test
    @RequiresFeature("request_boundaries")
    void testRequestBoundaries(Connection connection) throws SQLException {
        connection.beginRequest();
        connection.endRequest();
    }
}
