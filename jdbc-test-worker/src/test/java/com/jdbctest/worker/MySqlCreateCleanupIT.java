package com.jdbctest.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdbctest.config.Config;
import com.jdbctest.extension.TestObjects;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/** CREATE rejection must retain uncertainty without treating container removal as cleanup. */
@Tag("integration")
class MySqlCreateCleanupIT {

    @TempDir
    Path workspace;

    @Test
    void binaryLogPrivilegeRejectionAndSuccessfulOwnedFunctionHaveIndependentEvidence()
        throws Exception {
        String password = "disposable-cleanup-fixture";
        try (var database = new GenericContainer<>("mysql:8.4")
            .withEnv("MYSQL_ROOT_PASSWORD", password)
            .withEnv("MYSQL_DATABASE", "qa_cleanup")
            .withCommand("--log-bin=fixture-bin", "--server-id=9371",
                "--log-bin-trust-function-creators=0")
            .withExposedPorts(3306).waitingFor(Wait.forListeningPort())) {
            database.start();
            String url = "jdbc:mysql://" + database.getHost() + ":"
                + database.getMappedPort(3306)
                + "/qa_cleanup?allowPublicKeyRetrieval=true&sslMode=DISABLED";
            try (Connection root = connect(url, "root", password)) {
                try (var sql = root.createStatement()) {
                    sql.execute("CREATE USER 'qa_limited'@'%' IDENTIFIED BY '"
                        + password + "'");
                    sql.execute("GRANT ALL ON qa_cleanup.* TO 'qa_limited'@'%'");
                    sql.execute("CREATE TABLE qa_cleanup.owned_sentinel (id INT PRIMARY KEY)");
                    sql.executeUpdate("INSERT INTO qa_cleanup.owned_sentinel VALUES (17)");
                }
                var evidence = new LinkedHashMap<String, Object>();
                evidence.put("databaseVersion", root.getMetaData().getDatabaseProductVersion());
                evidence.put("driverVersion", root.getMetaData().getDriverVersion());
                try (var sql = root.createStatement();
                    var rows = sql.executeQuery(
                        "SELECT @@log_bin,@@log_bin_trust_function_creators")) {
                    assertTrue(rows.next());
                    assertEquals(1, rows.getInt(1));
                    assertEquals(0, rows.getInt(2));
                    evidence.put("fixturePrerequisites", Map.of("log_bin", rows.getInt(1),
                        "log_bin_trust_function_creators", rows.getInt(2)));
                }
                evidence.put("before", inventory(root));
                try (Connection limited = connect(url, "qa_limited", password);
                    var sql = limited.createStatement()) {
                    var denied = objects("denied");
                    SQLException error = assertThrows(SQLException.class, () -> denied.create(sql,
                        "CREATE FUNCTION {{add_numbers}}(a INT,b INT) RETURNS INT DETERMINISTIC "
                            + "RETURN a+b"));
                    assertEquals(1419, error.getErrorCode());
                    denied.cleanup(limited);
                    var cleanup = denied.cleanupEvidence();
                    assertEquals("UNKNOWN", cleanup.get("status"));
                    assertFalse(denied.snapshot().getFirst().containsKey("cleanupAttempted"));
                    assertFalse(functionExists(root, denied.name("add_numbers")));
                    evidence.put("denied", Map.of("sqlState", error.getSQLState(), "vendorCode",
                        error.getErrorCode(), "cleanup", cleanup,
                        "independentExactFunctionExists", false));
                }
                var allowed = objects("allowed");
                try (var sql = root.createStatement()) {
                    allowed.create(sql,
                        "CREATE FUNCTION {{add_numbers}}(a INT,b INT) RETURNS INT DETERMINISTIC "
                            + "RETURN a+b");
                    assertTrue(functionExists(root, allowed.name("add_numbers")));
                    try (var rows = sql.executeQuery("SELECT "
                        + allowed.qualified("add_numbers") + "(1,2)")) {
                        assertTrue(rows.next());
                        assertEquals(3, rows.getInt(1));
                    }
                } finally {
                    allowed.cleanup(root);
                    evidence.put("allowed", allowed.cleanupEvidence());
                }
                assertEquals("SUCCESS", allowed.cleanupEvidence().get("status"));
                assertFalse(functionExists(root, allowed.name("add_numbers")));
                evidence.put("after", inventory(root));
                assertEquals(evidence.get("before"), evidence.get("after"));
                try (var sql = root.createStatement();
                    var rows = sql.executeQuery("SELECT id FROM qa_cleanup.owned_sentinel")) {
                    assertTrue(rows.next());
                    assertEquals(17, rows.getInt(1));
                    assertFalse(rows.next());
                }
                String output = System.getProperty("jdbc.test.cleanupEvidenceDir");
                if (output != null) {
                    Path directory = Files.createDirectories(Path.of(output));
                    new ObjectMapper().writeValue(directory.resolve("mysql-create-cleanup.json")
                        .toFile(), evidence);
                    for (String name : List.of("denied", "allowed")) {
                        Files.copy(workspace.resolve(name + ".yaml"),
                            directory.resolve(name + "-journal.yaml"));
                    }
                }
                try (var sql = root.createStatement()) {
                    sql.execute("DROP TABLE qa_cleanup.owned_sentinel");
                }
            }
        }
    }

    private Connection connect(String url, String user, String password) throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }

    private TestObjects objects(String label) {
        var config = new Config();
        config.db = new Config.DbConfig();
        config.db.dialect = "mysql";
        config.db.identifierQuote = "`";
        config.namespace = new Config.NamespaceConfig();
        config.namespace.name = "qa_cleanup";
        config.namespace.selection = "catalog";
        config.namespace.destructiveConsent = true;
        config.namespace.objectPrefix = "j" + UUID.randomUUID().toString().replace("-", "")
            .substring(0, 20);
        config.namespace.ledgerPath = workspace.resolve(label + ".yaml").toString();
        return new TestObjects(config);
    }

    private boolean functionExists(Connection root, String name) throws SQLException {
        try (var sql = root.prepareStatement("SELECT ROUTINE_NAME FROM information_schema.ROUTINES "
            + "WHERE ROUTINE_SCHEMA='qa_cleanup' AND ROUTINE_NAME=?")) {
            sql.setString(1, name);
            try (var rows = sql.executeQuery()) {
                return rows.next();
            }
        }
    }

    private Map<String, List<String>> inventory(Connection root) throws SQLException {
        var result = new LinkedHashMap<String, List<String>>();
        Map<String, String> queries = Map.of(
            "functions", "SELECT ROUTINE_NAME FROM information_schema.ROUTINES "
                + "WHERE ROUTINE_SCHEMA='qa_cleanup' ORDER BY ROUTINE_NAME",
            "tables", "SELECT TABLE_NAME FROM information_schema.TABLES "
                + "WHERE TABLE_SCHEMA='qa_cleanup' ORDER BY TABLE_NAME",
            "xa", "XA RECOVER CONVERT XID");
        for (var query : queries.entrySet()) {
            var values = new ArrayList<String>();
            try (var sql = root.createStatement(); var rows = sql.executeQuery(query.getValue())) {
                while (rows.next()) {
                    values.add(rows.getString(1));
                }
            }
            result.put(query.getKey(), values);
        }
        return result;
    }
}
