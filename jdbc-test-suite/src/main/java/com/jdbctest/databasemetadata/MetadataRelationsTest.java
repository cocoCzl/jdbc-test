package com.jdbctest.databasemetadata;

import static org.junit.jupiter.api.Assertions.*;

import com.jdbctest.config.ConfigLoader;
import com.jdbctest.extension.JdbcContext;
import com.jdbctest.extension.JdbcTestExtension;
import com.jdbctest.extension.UseSqlScripts;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(JdbcTestExtension.class)
@Tag("integration")
@UseSqlScripts(ddl = "metadata_relations_ddl.sql")
class MetadataRelationsTest {

    private String catalog(Connection connection) throws SQLException {
        return ConfigLoader.load().db.isDialect("mysql")
            ? ConfigLoader.load().namespace.name : connection.getCatalog();
    }

    private String schema() {
        return ConfigLoader.load().db.isDialect("mysql") ? null
            : ConfigLoader.load().namespace.name;
    }

    private void assertRelation(ResultSet rows) throws SQLException {
        assertTrue(rows.next(), "Fixture foreign key must be discoverable");
        assertEquals(JdbcContext.objectName("metadata_parent"), rows.getString("PKTABLE_NAME"));
        assertEquals(JdbcContext.objectName("metadata_child"), rows.getString("FKTABLE_NAME"));
        assertEquals("id", rows.getString("PKCOLUMN_NAME").toLowerCase(Locale.ROOT));
        assertEquals("parent_id", rows.getString("FKCOLUMN_NAME").toLowerCase(Locale.ROOT));
        assertEquals(1, rows.getShort("KEY_SEQ"));
        assertFalse(rows.next(), "Fixture has exactly one foreign key column");
    }

    @Test
    @DisplayName("getImportedKeys 返回自建外键关系")
    void testImportedKeys(Connection connection) throws SQLException {
        try (
            ResultSet rows = connection.getMetaData().getImportedKeys(catalog(connection), schema(),
                JdbcContext.objectName("metadata_child"))) {
            assertRelation(rows);
        }
    }

    @Test
    @DisplayName("getExportedKeys 返回引用自建主键的表")
    void testExportedKeys(Connection connection) throws SQLException {
        try (
            ResultSet rows = connection.getMetaData().getExportedKeys(catalog(connection), schema(),
                JdbcContext.objectName("metadata_parent"))) {
            assertRelation(rows);
        }
    }

    @Test
    @DisplayName("getIndexInfo 返回自建索引和列顺序")
    void testIndexInfo(Connection connection) throws SQLException {
        boolean found = false;
        try (ResultSet rows = connection.getMetaData().getIndexInfo(catalog(connection), schema(),
            JdbcContext.objectName("metadata_child"), false, false)) {
            while (rows.next()) {
                if (JdbcContext.objectName("metadata_child_index")
                    .equals(rows.getString("INDEX_NAME"))) {
                    assertEquals("parent_id",
                        rows.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
                    assertEquals(1, rows.getShort("ORDINAL_POSITION"));
                    assertTrue(rows.getBoolean("NON_UNIQUE"));
                    found = true;
                }
            }
        }
        assertTrue(found, "Owned index must appear in metadata");
    }

    @Test
    @DisplayName("getTables 返回自建视图")
    void testViewMetadata(Connection connection) throws SQLException {
        String escape = connection.getMetaData().getSearchStringEscape();
        String pattern = JdbcContext.objectName("metadata_view");
        if (escape != null && !escape.isEmpty()) {
            pattern = pattern.replace("_", escape + "_");
        }
        try (ResultSet rows = connection.getMetaData()
            .getTables(catalog(connection), schema(), pattern,
                new String[] {"VIEW"})) {
            assertTrue(rows.next());
            assertEquals(JdbcContext.objectName("metadata_view"), rows.getString("TABLE_NAME"));
            assertEquals("VIEW", rows.getString("TABLE_TYPE"));
            assertFalse(rows.next());
        }
        try (Statement sql = connection.createStatement();
            ResultSet rows = sql.executeQuery(
                JdbcContext.sql("SELECT id FROM {{metadata_view}}"))) {
            assertFalse(rows.next(), "View must query the empty fixture parent table");
        }
    }
}
