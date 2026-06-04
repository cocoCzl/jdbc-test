package com.jdbctest.extension;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SqlSplitterTest {

    @Test
    void semicolonSplitterIgnoresStringsAndComments() {
        String sql = """
                CREATE TABLE t (name VARCHAR(20));
                INSERT INTO t VALUES ('a;b');
                -- SELECT 1;
                INSERT INTO t VALUES ('c');
                """;

        String[] statements = JdbcTestExtension.splitBySemicolon(sql);

        assertEquals(3, statements.length);
        assertEquals("CREATE TABLE t (name VARCHAR(20))", statements[0]);
        assertEquals("INSERT INTO t VALUES ('a;b')", statements[1]);
        assertEquals("-- SELECT 1;\nINSERT INTO t VALUES ('c')", statements[2]);
    }

    @Test
    void semicolonSplitterKeepsPostgresDollarQuotedBodyTogether() {
        String sql = """
                CREATE FUNCTION f() RETURNS void AS $$
                BEGIN
                  RAISE NOTICE 'hello; world';
                END;
                $$ LANGUAGE plpgsql;
                SELECT 1;
                """;

        String[] statements = JdbcTestExtension.splitBySemicolon(sql);

        assertEquals(2, statements.length);
        assertEquals("SELECT 1", statements[1]);
    }

    @Test
    void goSplitterSplitsSqlServerBatches() {
        String[] statements = JdbcTestExtension.splitByGo("""
                CREATE TABLE t (id int)
                GO
                INSERT INTO t VALUES (1)
                go
                """);

        assertArrayEquals(new String[]{
                "CREATE TABLE t (id int)",
                "INSERT INTO t VALUES (1)"
        }, statements);
    }

    @Test
    void slashSplitterKeepsOracleBlockTogether() {
        String[] statements = JdbcTestExtension.splitBySlash("""
                BEGIN
                  NULL;
                END;
                /
                CREATE TABLE t (id NUMBER);
                /
                """);

        assertEquals(2, statements.length);
        assertEquals("BEGIN\n  NULL;\nEND;", statements[0]);
        assertEquals("CREATE TABLE t (id NUMBER)", statements[1]);
    }
}
