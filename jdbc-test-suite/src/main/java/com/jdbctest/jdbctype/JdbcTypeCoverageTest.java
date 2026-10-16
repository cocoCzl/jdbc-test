package com.jdbctest.jdbctype;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jdbctest.extension.JdbcTestExtension;
import com.jdbctest.extension.RequiresFeature;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.sql.Types;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(JdbcTestExtension.class)
@org.junit.jupiter.api.Tag("integration")
class JdbcTypeCoverageTest {

    @Test
    void testCommonTypeMetadata(Connection connection) throws SQLException {
        Set<Integer> types = typeCodes(connection);
        assertTrue(types.contains(Types.INTEGER) || types.contains(Types.BIGINT));
        assertTrue(types.contains(Types.VARCHAR) || types.contains(Types.NVARCHAR));
        assertTrue(types.contains(Types.DATE) || types.contains(Types.TIMESTAMP)
            || types.contains(Types.TIMESTAMP_WITH_TIMEZONE));
    }

    @Test
    @RequiresFeature("array_type")
    void testArrayTypeMetadata(Connection connection) throws SQLException {
        assertTrue(typeCodes(connection).contains(Types.ARRAY));
    }

    @Test
    @RequiresFeature("sqlxml")
    void testSqlXmlApi(Connection connection) throws SQLException {
        SQLXML xml = connection.createSQLXML();
        assertNotNull(xml);
        xml.free();
    }

    @Test
    @RequiresFeature("rowid_type")
    void testRowIdTypeMetadata(Connection connection) throws SQLException {
        assertTrue(typeCodes(connection).contains(Types.ROWID));
    }

    @Test
    @RequiresFeature("ref_type")
    void testRefTypeMetadata(Connection connection) throws SQLException {
        assertTrue(typeCodes(connection).contains(Types.REF));
    }

    @Test
    @RequiresFeature("struct_type")
    void testStructTypeMetadata(Connection connection) throws SQLException {
        assertTrue(typeCodes(connection).contains(Types.STRUCT));
    }

    private Set<Integer> typeCodes(Connection connection) throws SQLException {
        Set<Integer> result = new HashSet<>();
        try (ResultSet rows = connection.getMetaData().getTypeInfo()) {
            while (rows.next()) {
                result.add(rows.getInt("DATA_TYPE"));
            }
        }
        return result;
    }
}
