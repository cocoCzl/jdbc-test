package com.jdbctest.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

class SqlTestPackageTest {

    private String example() throws Exception {
        return Files.readString(Path.of("../examples/sql/postgresql-basics.json"));
    }

    @Test
    void fullExampleIsVersionedExactAndImmutable() throws Exception {
        var first = new SqlTestPackage(example(), null);
        assertEquals(4, first.definition().cases().size());
        assertEquals(TestCategory.SQL_FEATURE, first.definition().cases().getFirst().descriptor().category());
        assertEquals(first, SqlTestPackage.read(new ByteArrayInputStream(example().getBytes(StandardCharsets.UTF_8))));
        assertNotEquals(first.fingerprint(), new SqlTestPackage(example().replace("1.0.0", "1.0.1"), null).fingerprint());
        assertThrows(IllegalArgumentException.class, () -> new SqlTestPackage(example(), "a".repeat(64)));
        assertThrows(UnsupportedOperationException.class, () -> first.definition().cases().clear());
    }

    @Test
    void mysqlExampleKeepsExplicitSyntaxAndErrorContract() throws Exception {
        var bundle = new SqlTestPackage(Files.readString(
            Path.of("../examples/sql/mysql-basics.json")), null);
        assertEquals(4, bundle.definition().cases().size());
        assertTrue(bundle.manifest().contains("CAST(? AS SIGNED)"));
        assertTrue(bundle.manifest().contains("\"vendorCode\": 1062"));
        assertTrue(bundle.definition().cases().stream().allMatch(
            item -> item.variants().containsKey("mysql")));
    }

    @Test
    void invalidDeclarationsFailClosedWithFieldDiagnostics() throws Exception {
        String source = example();
        for (String invalid : new String[] {
            source.replace("\"formatVersion\": 1", "\"formatVersion\": 2"),
            source.replace("\"formatVersion\": 1", "\"formatVersion\": 1, \"unknown\": true"),
            source.replace("\"postgresql\"", "\"unknown_dialect\""),
            source.replace("\"INTEGER\"", "\"FLOAT\""),
            source.replace("SELECT 1 AS n", "SELECT 1; SELECT 2"),
            source.replace("SELECT 1 AS n", "create table unowned(x integer)"),
            source.replace("\"22012\"", "\"42501\""),
            source.replace("\"cleanup\": [\n            \"items\"\n          ]", "\"cleanup\": []"),
            source.replace("\"value\": \"42\"", "\"value\": 42"),
            source.replace("\"value\": \"42\"", "\"value\": \"2147483648\""),
            source.replace("\"decimalMode\": \"SCALE\"", "\"decimalMode\": \"STRING\"")
        }) {
            assertNotEquals(source, invalid);
            assertThrows(IllegalArgumentException.class, () -> new SqlTestPackage(invalid, null));
        }
    }

    @Test
    void archiveUsesSharedTraversalAndResourceBoundary() throws Exception {
        byte[] source = example().getBytes(StandardCharsets.UTF_8);
        assertEquals(new SqlTestPackage(example(), null), SqlTestPackage.readZip(new ByteArrayInputStream(zip("sql-package.json", source))));
        assertEquals(SqlTestPackage.readPackage(new ByteArrayInputStream(source)),
            SqlTestPackage.readPackage(new ByteArrayInputStream(zip("sql-package.json", source))));
        for (String path : new String[] {"../sql-package.json", "/sql-package.json", "dir/sql-package.json", "driver.jar"}) {
            assertThrows(IllegalArgumentException.class, () -> SqlTestPackage.readZip(new ByteArrayInputStream(zip(path, source))));
        }
        assertThrows(IllegalArgumentException.class, () -> SqlTestPackage.read(new ByteArrayInputStream("{\"id\":1,\"id\":2}".getBytes(StandardCharsets.UTF_8))));
        assertThrows(IllegalArgumentException.class, () -> SqlTestPackage.read(new ByteArrayInputStream(new byte[1_048_577])));
    }

    @Test
    void nullDecimalAndIntegerAreNotStringCoercions() {
        assertEquals(null, SqlTestPackage.canonical("STRING", null, "NUMERIC"));
        assertNotEquals(null, SqlTestPackage.canonical("STRING", "NULL", "NUMERIC"));
        assertEquals(SqlTestPackage.canonical("DECIMAL", "1.20", "NUMERIC"), SqlTestPackage.canonical("DECIMAL", "1.2", "NUMERIC"));
        assertNotEquals(SqlTestPackage.canonical("DECIMAL", "1.20", "SCALE"), SqlTestPackage.canonical("DECIMAL", "1.2", "SCALE"));
        assertTrue(SqlTestPackage.canonical("INTEGER", "9223372036854775808123", "SCALE") instanceof java.math.BigInteger);
    }

    private byte[] zip(String name, byte[] bytes) throws Exception {
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(bytes);
            zip.closeEntry();
        }
        return output.toByteArray();
    }
}
