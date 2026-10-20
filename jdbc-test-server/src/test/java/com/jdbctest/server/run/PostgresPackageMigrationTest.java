package com.jdbctest.server.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jdbctest.server.JdbcTestServerApplication;
import com.jdbctest.server.connection.AdapterService;
import com.jdbctest.server.connection.DatabaseConnectionProfileRepository;
import com.jdbctest.server.driver.JdbcDriverPackageRepository;
import com.jdbctest.server.sql.SqlPackageService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.ObjectMapper;

/** Explicit external URL must identify this session's disposable PostgreSQL, never a platform DB. */
@EnabledIfEnvironmentVariable(named = "JDBC_TEST_DISPOSABLE_PG_URL", matches = "jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/postgres")
class PostgresPackageMigrationTest {

    @TempDir
    Path directory;

    @Test
    void emptyAndPopulatedV8MigrateAndRecoverAcrossRestart() throws Exception {
        verifyDatabase(false);
        verifyDatabase(true);
    }

    private void verifyDatabase(boolean upgrade) throws Exception {
        String schema = "phase3_" + UUID.randomUUID().toString().replace("-", "");
        String base = System.getenv("JDBC_TEST_DISPOSABLE_PG_URL");
        var admin = new JdbcTemplate(new DriverManagerDataSource(base, "postgres", ""));
        admin.execute("CREATE SCHEMA " + schema);
        String url = base + "?currentSchema=" + schema;
        var source = new DriverManagerDataSource(url, "postgres", "");
        var sql = new JdbcTemplate(source);
        UUID driver = UUID.randomUUID();
        UUID connection = UUID.randomUUID();
        UUID run = UUID.randomUUID();
        Path data = directory.resolve(schema);
        try {
            if (upgrade) {
                Flyway.configure().dataSource(source).defaultSchema(schema).target("8").load().migrate();
                sql.update("insert into jdbc_driver_package(id,name,vendor,version,driver_class,source_type,status,package_fingerprint,created_at,updated_at) values (?,'synthetic driver','fixture','A','fixture.Driver','UPLOAD','VALIDATED',?,now(),now())", driver, "a".repeat(64));
                sql.update("insert into jdbc_driver_artifact(id,driver_id,file_name,storage_key,size,sha256,created_at) values (?,?,'old.jar','drivers/old.jar',3,?,now())", UUID.randomUUID(), driver, "b".repeat(64));
                sql.update("insert into database_connection_profile(id,name,database_type,adapter_id,jdbc_url,username,default_driver_id,created_at,updated_at) values (?,'synthetic connection','postgresql','postgresql','jdbc:fixture:never-contacted','fixture',?,now(),now())", connection, driver);
                sql.update("insert into test_run(id,connection_id,driver_id,status,profile,created_at,total_cases,completed_cases,failed,connection_name_snapshot,database_type_snapshot,jdbc_url_sanitized_snapshot,driver_name_snapshot,driver_vendor_snapshot,driver_version_snapshot,driver_class_snapshot,adapter_id_snapshot) values (?,?,?,'FAILED','CUSTOM',now(),1,1,1,'synthetic connection','postgresql','jdbc:fixture:never-contacted','synthetic driver','fixture','A','fixture.Driver','postgresql')", run, connection, driver);
                sql.update("insert into test_case_result(id,run_id,scenario_id,module_id,display_name,status,message) values (?,?,'legacy.case','connection','legacy failure','FAILED','synthetic old failure')", UUID.randomUUID(), run);
                Files.createDirectories(data.resolve("drivers"));
                Files.write(data.resolve("drivers/old.jar"), new byte[] {1, 2, 3});
                Path report = data.resolve("runs/" + run + "/report/report.json");
                Files.createDirectories(report.getParent());
                Files.writeString(report, "{\"report_schema_version\":\"1.0.0\",\"synthetic\":true}");
            }
            String[] args = {"--spring.datasource.url=" + url,
                "--spring.datasource.username=postgres", "--spring.datasource.password=",
                "--spring.flyway.default-schema=" + schema,
                "--jdbc-test.storage.data-directory=" + data,
                "--jdbc-test.security.master-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="};
            String fingerprint;
            String sqlFingerprint;
            String frozen;
            try (var context = new SpringApplicationBuilder(JdbcTestServerApplication.class)
                .web(WebApplicationType.NONE).run(args)) {
                assertEquals(10, sql.queryForObject("select max(version::integer) from flyway_schema_history where success", Integer.class));
                fingerprint = context.getBean(AdapterService.class).importPackage(
                    ImportedAdapterFlowTest.bundle("migration-fixture", "1", false), null).fingerprint();
                sqlFingerprint = context.getBean(SqlPackageService.class).importPackage(new MockMultipartFile(
                    "file", "sql-package.json", "application/json", Files.readAllBytes(Path.of("../examples/sql/postgresql-basics.json")))).fingerprint();
                frozen = new ObjectMapper().writeValueAsString(Map.of("adapterPackage",
                    context.getBean(AdapterService.class).packageSnapshot("migration-fixture"),
                    "sqlPackage", context.getBean(SqlPackageService.class).required(sqlFingerprint)));
                if (upgrade) {
                    assertNotNull(context.getBean(JdbcDriverPackageRepository.class).findById(driver).orElseThrow());
                    assertEquals("synthetic connection", context.getBean(DatabaseConnectionProfileRepository.class).findById(connection).orElseThrow().getName());
                    assertEquals("synthetic old failure", context.getBean(TestCaseResultRepository.class).findByRun_Id(run).getFirst().getMessage());
                    assertEquals(3, Files.size(data.resolve("drivers/old.jar")));
                    assertTrue(Files.readString(data.resolve("runs/" + run + "/report/report.json")).contains("synthetic"));
                    sql.update("update test_run set execution_input=? where id=?", frozen, run);
                }
            }
            try (var reopened = new SpringApplicationBuilder(JdbcTestServerApplication.class)
                .web(WebApplicationType.NONE).run(args)) {
                var snapshot = reopened.getBean(AdapterService.class).packageSnapshot("migration-fixture");
                assertEquals(fingerprint, snapshot.fingerprint());
                assertTrue(!snapshot.resources().isEmpty());
                assertEquals(sqlFingerprint, reopened.getBean(SqlPackageService.class).required(sqlFingerprint).fingerprint());
                if (upgrade) {
                    assertEquals(frozen, reopened.getBean(TestRunRepository.class).findById(run).orElseThrow().getExecutionInput());
                }
            }
        } finally {
            admin.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }
}
