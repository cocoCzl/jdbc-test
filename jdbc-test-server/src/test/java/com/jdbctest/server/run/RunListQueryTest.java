package com.jdbctest.server.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jdbctest.server.connection.DatabaseConnectionProfileEntity;
import com.jdbctest.server.connection.DatabaseConnectionProfileRepository;
import com.jdbctest.server.driver.DriverSourceType;
import com.jdbctest.server.driver.JdbcDriverPackageEntity;
import com.jdbctest.server.driver.JdbcDriverPackageRepository;
import com.jdbctest.server.driver.WorkerProcessRunner;
import java.util.List;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Measures real SQL and entity loads on H2; no target driver or database is used. */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:list-query-cost;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=", "spring.flyway.enabled=false",
    "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.properties.hibernate.generate_statistics=true",
    "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.jdbctest.server.run.RunListQueryTest$SqlCapture",
    "logging.level.org.hibernate.stat=OFF", "logging.level.org.hibernate.orm.session=OFF",
    "jdbc-test.security.master-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
@Transactional
class RunListQueryTest {

    @Autowired
    TestRunService service;
    @Autowired
    TestRunRepository runs;
    @Autowired
    TestRunSelectedScenarioRepository selections;
    @Autowired
    TestCaseResultRepository results;
    @Autowired
    JdbcDriverPackageRepository drivers;
    @Autowired
    DatabaseConnectionProfileRepository connections;
    @Autowired
    jakarta.persistence.EntityManager entityManager;
    @Autowired
    JdbcTemplate sql;
    @Autowired
    ObjectMapper mapper;
    @MockitoBean
    TestRunScheduler scheduler;
    @MockitoBean
    WorkerProcessRunner worker;

    @Test
    void listLoadsOnlyPageEntitiesAndGroupedCountsAsCaseCountGrows() {
        var driver = drivers.save(new JdbcDriverPackageEntity("fixture", "fixture", "1", "fixture.Driver", DriverSourceType.UPLOAD));
        var connection = connections.save(new DatabaseConnectionProfileEntity("fixture", "postgresql", "postgresql",
            "jdbc:fixture:never-contacted", "fixture"));
        for (int count : List.of(20, 1000)) {
            for (int index = 0; index < 12; index++) {
                var run = new TestRunEntity(connection, driver, "FULL", "fixture", "postgresql", "postgresql",
                    "jdbc:fixture:never-contacted", "fixture", "fixture", "1", "fixture.Driver", null);
                run.setStatus(TestRunStatus.SUCCEEDED);
                run.freezeExecutionInput("{\"scope\":{},\"selection\":{\"selectedMetadata\":[" +
                    String.join(",", java.util.Collections.nCopies(count, "{\"id\":\"fixture\"}")) + "]}}");
                run.setTotalCases(count);
                for (int item = 0; item < count; item++) {
                    run.recordCase(TestCaseResultStatus.PASSED);
                }
                runs.saveAndFlush(run);
                sql.batchUpdate("insert into test_run_selected_scenario(id,run_id,scenario_id,module_id,execution_started_at,result_status) values (?,?,?,?,CURRENT_TIMESTAMP,'PASSED')",
                    java.util.stream.IntStream.range(0, count).mapToObj(item -> new Object[] {
                        java.util.UUID.randomUUID(), run.getId(), "case." + item, "connection"}).toList());
                sql.batchUpdate("insert into test_case_result(id,run_id,scenario_id,module_id,display_name,status,execution_evidence,contract_conclusion,contract_invoked) values (?,?,?,?,?,'PASSED',?,'CONTRACT_SATISFIED',true)",
                    java.util.stream.IntStream.range(0, count).mapToObj(item -> new Object[] {
                        java.util.UUID.randomUUID(), run.getId(), "case." + item, "connection", "fixture",
                        "{\"conclusion\":\"CONTRACT_SATISFIED\",\"contractInvoked\":true,\"padding\":\"" + "x".repeat(1024) + "\"}"}).toList());
            }
            // Deliberately tie primary sort keys to exercise stable page boundaries.
            sql.update("update test_run set created_at=TIMESTAMP '2026-01-01 00:00:00'");
            entityManager.clear();
            var statistics = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
            statistics.clear();
            // Reproduce the old list's per-run selection, accounting and full evidence reads.
            var oldPage = runs.findAll(PageRequest.of(0, 10));
            for (var run : oldPage) {
                selections.findByRun_IdOrderByScenarioId(run.getId());
                selections.findByRun_IdOrderByScenarioId(run.getId());
                results.findByRun_Id(run.getId()).forEach(row -> mapper.readTree(row.getExecutionEvidence()));
            }
            long oldQueries = statistics.getPrepareStatementCount();
            long oldEntities = statistics.getEntityLoadCount();
            entityManager.clear();
            statistics.clear();
            SqlCapture.queries.clear();
            SqlCapture.enabled = true;
            PageResponse<TestRunResponse> response;
            try {
                response = service.list(0, 10, "createdAt,asc", null);
            } finally {
                SqlCapture.enabled = false;
            }
            assertTrue(SqlCapture.queries.stream().noneMatch(query -> query.contains("execution_input")),
                "list SQL must exclude the complete frozen per-case snapshot");
            assertTrue(SqlCapture.queries.stream().noneMatch(query -> query.contains("execution_evidence")),
                "list SQL must not read individual case evidence");
            assertEquals(10, response.items().size());
            org.slf4j.LoggerFactory.getLogger(getClass()).info("LIST_SQL {}", SqlCapture.queries);
            long queries = statistics.getPrepareStatementCount();
            long entities = statistics.getEntityLoadCount();
            int bytes = mapper.writeValueAsBytes(response).length;
            assertEquals(4, queries, "page + count + selection aggregates + contract aggregates");
            assertEquals(0, entities, "closed scalar projection loads no full run, scenario or case entities");
            assertTrue(response.items().stream().allMatch(run -> run.accounting().scenarios().isEmpty()));
            assertEquals((long) count, response.items().getFirst().contractSummary().get("EXECUTED"));
            assertTrue(bytes < 30_000, "response omits per-case metadata/evidence");
            org.slf4j.LoggerFactory.getLogger(getClass()).info(
                "LIST_COST runs=12 casesPerRun={} page=10 oldQueries={} oldEntityLoads={} queries={} entityLoads={} bytes={}",
                count, oldQueries, oldEntities, queries, entities, bytes);
            var following = service.list(1, 10, "createdAt,asc", null);
            var actualIds = java.util.stream.Stream.concat(response.items().stream(), following.items().stream())
                .map(TestRunResponse::id).toList();
            var expectedIds = sql.queryForList("select id from test_run order by created_at, id", java.util.UUID.class);
            assertEquals(expectedIds, actualIds, "tied sort keys must produce complete, nonoverlapping pages");
            var pageIds = response.items().stream().map(TestRunResponse::id).toList();
            int selectionRows = selections.summarize(pageIds).size();
            int contractRows = results.summarize(pageIds).size();
            assertEquals(10, selectionRows);
            assertEquals(10, contractRows);
            org.slf4j.LoggerFactory.getLogger(getClass()).info(
                "LIST_ROWS page=10 count=1 selectionGroups={} contractGroups={} scalarRows={} aggregateInputCases={}",
                selectionRows, contractRows, 11 + selectionRows + contractRows, count * 10);
            sql.update("delete from test_case_result");
            sql.update("delete from test_run_selected_scenario");
            sql.update("delete from test_run");
            entityManager.clear();
        }
    }
    public static final class SqlCapture implements org.hibernate.resource.jdbc.spi.StatementInspector {

        private static final java.util.List<String> queries = new java.util.concurrent.CopyOnWriteArrayList<>();
        private static volatile boolean enabled;

        @Override
        public String inspect(String sql) {
            if (enabled) {
                queries.add(sql);
            }
            return sql;
        }
    }

}
