package com.jdbctest.extension;

import static org.junit.jupiter.api.Assertions.*;

import com.jdbctest.catalog.AdapterExecutionConfig;
import com.jdbctest.config.Config;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Checks actual packaged fixtures through the production splitter and ownership machinery.
 */
class MigrationAssetsTest {

    @TempDir
    Path directory;

    @Test
    void enabledMigrationFixturesArePackagedSplitAndOwned() throws Exception {
        Map<String, List<String>> fixtures = Map.ofEntries(
            Map.entry("transaction_behavior", List.of()),
            Map.entry("batch_behavior", List.of()),
            Map.entry("lob_persistence", List.of("blobs", "clobs")),
            Map.entry("metadata_relations", List.of()),
            Map.entry("temporal_precision", List.of()),
            Map.entry("fetch_window", List.of()),
            Map.entry("sql_syntax_behavior", List.of()),
            Map.entry("xa_behavior", List.of("xa_data_source")),
            Map.entry("locking_behavior", List.of("query_timeout")),
            Map.entry("bulk_transaction", List.of()),
            Map.entry("sqlxml_stream", List.of("sqlxml")),
            Map.entry("procedure_behavior", List.of("callable_statement", "callable_inout")),
            Map.entry("ref_cursor", List.of("callable_statement", "ref_cursor")),
            Map.entry("rowid_behavior", List.of("rowid_operations")),
            Map.entry("multiple_results",
                List.of("callable_statement", "multiple_result_sets",
                    "multiple_result_sequences")));
        for (String adapter : List.of("mysql", "postgresql", "oracle", "sqlserver", "gaussdb")) {
            var execution = AdapterExecutionConfig.load(adapter);
            for (var fixture : fixtures.entrySet()) {
                if (!fixture.getValue().stream().allMatch(execution.capabilities()::get)) {
                    continue;
                }
                String resource = "ddl/" + adapter + "/" + fixture.getKey() + "_ddl.sql";
                String content;
                try (var input = getClass().getClassLoader().getResourceAsStream(resource)) {
                    assertNotNull(input, resource);
                    content = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                }
                String[] statements =
                    adapter.equals("oracle") ? JdbcTestExtension.splitBySlash(content)
                        : adapter.equals("sqlserver") ? JdbcTestExtension.splitByGo(content)
                            : JdbcTestExtension.splitBySemicolon(content);
                Config config = new Config();
                config.db = new Config.DbConfig();
                config.db.dialect = adapter;
                config.db.identifierQuote = execution.identifierQuote();
                config.namespace = new Config.NamespaceConfig();
                config.namespace.name = "migration_fixture";
                config.namespace.selection = adapter.equals("mysql") ? "catalog" : "schema";
                config.namespace.destructiveConsent = true;
                config.namespace.objectPrefix = "j01234567890123456789";
                config.namespace.ledgerPath = directory.resolve(
                        adapter + "-" + fixture.getKey() + ".yaml")
                    .toString();
                TestObjects objects = new TestObjects(config);
                List<String> commands = new ArrayList<>();
                Statement sql = (Statement) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] {Statement.class}, (proxy, method, arguments) -> {
                        if (method.getName().equals("executeUpdate")) {
                            commands.add((String) arguments[0]);
                            return 0;
                        }
                        if (method.getName().equals("execute")) {
                            commands.add((String) arguments[0]);
                            return false;
                        }
                        return null;
                    });
                for (String statement : statements) {
                    JdbcTestExtension.executeManagedStatement(sql, objects, statement);
                }
                int expected = switch (fixture.getKey()) {
                    case "metadata_relations" -> 4;
                    case "multiple_results" -> 2;
                    default -> 1;
                };
                assertEquals(expected, objects.snapshot().size(), resource);
                assertTrue(
                    objects.snapshot().stream().allMatch(row -> row.get("state").equals("CREATED")),
                    resource);
                assertTrue(commands.stream().noneMatch(command -> command.contains("{{")),
                    resource);
                if (fixture.getKey().equals("multiple_results")) {
                    assertEquals(3, commands.size(),
                        "Procedure body must remain a single statement");
                    assertTrue(commands.get(2).contains("SELECT 22"));
                }
            }
        }
    }
}
