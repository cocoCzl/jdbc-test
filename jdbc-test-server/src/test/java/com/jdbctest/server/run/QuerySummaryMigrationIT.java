package com.jdbctest.server.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class QuerySummaryMigrationIT {

    @Container
    static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:16");

    @Test
    void emptyDatabaseMigratesToLatestVersion() {
        var admin = new DriverManagerDataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
        new JdbcTemplate(admin).execute("create schema empty_upgrade");
        var source = new DriverManagerDataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
        var properties = new java.util.Properties();
        properties.setProperty("currentSchema", "empty_upgrade");
        source.setConnectionProperties(properties);
        assertEquals("15", Flyway.configure().dataSource(source).load().migrate().targetSchemaVersion);
        assertEquals(0, new JdbcTemplate(source).queryForObject("select count(*) from test_run", Integer.class));
    }

    @Test
    void upgradeBackfillsProjectionsWithoutChangingSqlServerHistoryOrMalformedEvidence() {
        var source = new DriverManagerDataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
        var sql = new JdbcTemplate(source);
        Flyway.configure().dataSource(source).target("12").load().migrate();
        UUID driver = UUID.randomUUID();
        UUID connection = UUID.randomUUID();
        UUID run = UUID.randomUUID();
        sql.update("insert into jdbc_driver_package(id,name,vendor,version,driver_class,source_type,status,created_at,updated_at) values (?,'fixture','fixture','1','fixture.Driver','UPLOAD','VALIDATED',now(),now())", driver);
        sql.update("insert into database_connection_profile(id,name,database_type,adapter_id,jdbc_url,username,created_at,updated_at) values (?,'history','sqlserver','sqlserver','jdbc:fixture:none','fixture',now(),now())", connection);
        sql.update("insert into test_run(id,connection_id,driver_id,status,profile,created_at,connection_name_snapshot,database_type_snapshot,adapter_id_snapshot,jdbc_url_sanitized_snapshot,driver_name_snapshot,driver_vendor_snapshot,driver_version_snapshot,driver_class_snapshot,cleanup_evidence,execution_issues) values (?,?,?,'FAILED','FULL',now(),'history','sqlserver','sqlserver','jdbc:fixture:none','fixture','fixture','1','fixture.Driver','{\"status\":\"SUCCESS\"}','[]')", run, connection, driver);
        String frozen = "{\"scope\":{},\"selection\":{\"catalogVersion\":\"historical\",\"selectedMetadata\":[{\"id\":\"original\"}]}}";
        sql.update("update test_run set execution_input=? where id=?", frozen, run);
        String evidence = "{\"conclusion\":\"NOT_EVALUATED\",\"requirementScope\":\"SUITE\",\"contractInvoked\":true}";
        sql.update("insert into test_case_result(id,run_id,scenario_id,module_id,display_name,status,execution_evidence) values (?,?,'valid','connection','fixture','FAILED',?)", UUID.randomUUID(), run, evidence);
        sql.update("insert into test_case_result(id,run_id,scenario_id,module_id,display_name,status,execution_evidence) values (?,?,'malformed','connection','fixture','FAILED','invalid historical JSON')", UUID.randomUUID(), run);
        // Cross two JDBC batch boundaries for both tables; include NULL and old-format evidence.
        sql.update("insert into test_run(id,connection_id,driver_id,status,profile,created_at,connection_name_snapshot,database_type_snapshot,adapter_id_snapshot,jdbc_url_sanitized_snapshot,driver_name_snapshot,driver_vendor_snapshot,driver_version_snapshot,driver_class_snapshot) select gen_random_uuid(),connection_id,driver_id,status,profile,created_at,connection_name_snapshot,database_type_snapshot,adapter_id_snapshot,jdbc_url_sanitized_snapshot,driver_name_snapshot,driver_vendor_snapshot,driver_version_snapshot,driver_class_snapshot from test_run cross join generate_series(1,1005) where id=?", run);
        sql.update("insert into test_case_result(id,run_id,scenario_id,module_id,display_name,status,execution_evidence) select gen_random_uuid(),?,'legacy.' || n,'connection','fixture','FAILED',case when n%3=0 then NULL when n%3=1 then '{}' else '{\"contractInvoked\":false}' end from generate_series(1,1005) n", run);
        // A deterministic late failure, after all batches, exercises PostgreSQL transactional DDL.
        sql.execute("create index idx_result_run_contract on test_run(id)");
        assertThrows(org.flywaydb.core.api.FlywayException.class,
            () -> Flyway.configure().dataSource(source).load().migrate());
        assertEquals("14", sql.queryForObject("select version from flyway_schema_history where success order by installed_rank desc limit 1", String.class));
        assertEquals(0, sql.queryForObject("select count(*) from information_schema.columns where table_schema='public' and table_name='test_run' and column_name='execution_summary'", Integer.class));
        assertEquals(0, sql.queryForObject("select count(*) from flyway_schema_history where version='15'", Integer.class));
        assertEquals(frozen, sql.queryForObject("select execution_input from test_run where id=?", String.class, run));
        assertEquals(1007, sql.queryForObject("select count(*) from test_case_result", Integer.class));
        sql.execute("drop index idx_result_run_contract");
        // Restart without repair or manual Flyway history edits.
        Flyway.configure().dataSource(source).load().migrate();
        assertEquals(1006, sql.queryForObject("select count(*) from test_run", Integer.class));
        assertEquals(1005, sql.queryForObject("select count(*) from test_run where execution_summary is null and not cleanup_succeeded and execution_issues_empty", Integer.class));
        assertEquals(1006, sql.queryForObject("select count(*) from test_case_result where contract_conclusion is null and not contract_invoked", Integer.class));
        assertEquals(0, Flyway.configure().dataSource(source).load().migrate().migrationsExecuted);
        assertEquals(frozen, sql.queryForObject("select execution_input from test_run where id=?", String.class, run));
        String summary = sql.queryForObject("select execution_summary from test_run where id=?", String.class, run);
        assertTrue(summary.contains("historical"));
        assertFalse(summary.contains("selectedMetadata"));
        assertEquals(evidence, sql.queryForObject("select execution_evidence from test_case_result where scenario_id='valid'", String.class));
        assertEquals("invalid historical JSON", sql.queryForObject("select execution_evidence from test_case_result where scenario_id='malformed'", String.class));
        assertEquals("NOT_EVALUATED", sql.queryForObject("select contract_conclusion from test_case_result where scenario_id='valid'", String.class));
        assertTrue(sql.queryForObject("select contract_invoked from test_case_result where scenario_id='valid'", Boolean.class));
        assertFalse(sql.queryForObject("select contract_invoked from test_case_result where scenario_id='malformed'", Boolean.class));
        assertTrue(sql.queryForObject("select cleanup_succeeded and execution_issues_empty from test_run where id=?", Boolean.class, run));
        assertEquals("FAILED", sql.queryForObject("select status from test_run where id=?", String.class, run));
        assertEquals("sqlserver", sql.queryForObject("select adapter_id from database_connection_profile where id=?", String.class, connection));
        assertEquals(0L, sql.queryForObject("select validation_revision from jdbc_driver_package where id=?", Long.class, driver));
        assertEquals(0L, sql.queryForObject("select event_sequence from test_run where id=?", Long.class, run));
    }
}
