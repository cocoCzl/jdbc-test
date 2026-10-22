package com.jdbctest.server.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jdbctest.server.connection.DatabaseConnectionProfileEntity;
import com.jdbctest.server.connection.DatabaseConnectionProfileRepository;
import com.jdbctest.server.driver.DriverSourceType;
import com.jdbctest.server.driver.JdbcDriverPackageEntity;
import com.jdbctest.server.driver.JdbcDriverPackageRepository;
import com.jdbctest.server.driver.WorkerProcessRunner;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/** Real H2 predicates, count queries and pagination; no target database or Worker runs. */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:run-status-filters;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=", "spring.flyway.enabled=false",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "jdbc-test.security.master-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
@Transactional
class RunStatusFilteringTest {

    @TempDir
    static Path directory;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("jdbc-test.storage.data-directory", () -> directory.toString());
    }

    @Autowired
    WebApplicationContext context;
    @Autowired
    TestRunRepository runs;
    @Autowired
    TestRunSelectedScenarioRepository selections;
    @Autowired
    jakarta.persistence.EntityManager entityManager;
    @Autowired
    JdbcDriverPackageRepository drivers;
    @Autowired
    DatabaseConnectionProfileRepository connections;
    @MockitoBean
    TestRunScheduler scheduler;
    @MockitoBean
    WorkerProcessRunner worker;
    MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        var driver = drivers.save(new JdbcDriverPackageEntity("fixture", "fixture", "1",
            "fixture.Driver", DriverSourceType.UPLOAD));
        var connection = new DatabaseConnectionProfileEntity("fixture", "postgresql", "postgresql",
            "jdbc:fixture:never-contacted", "fixture");
        connection.setDefaultDriver(driver);
        connection = connections.save(connection);
        for (TestRunStatus state : TestRunStatus.values()) {
            var run = runs.save(new TestRunEntity(connection, driver, "FULL", "fixture",
                "postgresql", "postgresql", "jdbc:fixture:never-contacted", "fixture", "fixture",
                "1", "fixture.Driver", null));
            // Seed historical states directly, including legacy PROBING, without execution.
            run.setStatus(state);
            ReflectionTestUtils.setField(run, "createdAt",
                Instant.parse("2026-01-01T00:00:00Z").plusSeconds(state.ordinal()));
        }
        runs.flush();
    }

    @Test
    void eachGroupHasExactlyItsDocumentedStates() throws Exception {
        Map<TestRunStatusGroup, List<TestRunStatus>> expected = Map.of(
            TestRunStatusGroup.ACTIVE, List.of(TestRunStatus.QUEUED, TestRunStatus.PREPARING,
                TestRunStatus.PROBING, TestRunStatus.RUNNING, TestRunStatus.GENERATING_REPORT),
            TestRunStatusGroup.ENDED, List.of(TestRunStatus.SUCCEEDED, TestRunStatus.FAILED,
                TestRunStatus.CANCELLED, TestRunStatus.INTERRUPTED),
            TestRunStatusGroup.WAITING, List.of(TestRunStatus.QUEUED),
            TestRunStatusGroup.IN_PROGRESS, List.of(TestRunStatus.PREPARING, TestRunStatus.PROBING,
                TestRunStatus.RUNNING, TestRunStatus.GENERATING_REPORT),
            TestRunStatusGroup.COMPLETED, List.of(TestRunStatus.SUCCEEDED),
            TestRunStatusGroup.ABNORMAL, List.of(TestRunStatus.FAILED, TestRunStatus.INTERRUPTED),
            TestRunStatusGroup.CANCELLED, List.of(TestRunStatus.CANCELLED));
        for (var entry : expected.entrySet()) {
            assertEquals(entry.getValue(), entry.getKey().statuses());
            var response = mvc.perform(get("/api/runs").param("statusGroup", entry.getKey().name())
                    .param("sort", "createdAt,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(entry.getValue().size()));
            for (int index = 0; index < entry.getValue().size(); index++) {
                response.andExpect(jsonPath("$.items[" + index + "].status")
                    .value(entry.getValue().get(index).name()));
            }
        }
        verifyNoInteractions(worker);
    }

    @Test
    void validatedHistoricalCaseFailuresAreCompletedWithoutChangingRawStatus() throws Exception {
        var run = runs.findAll().stream().filter(item -> item.getStatus() == TestRunStatus.FAILED)
            .findFirst().orElseThrow();
        run.setFailureReason("WORKER_EXECUTION_FAILED");
        run.setWorkerTerminationReason("TEST_FAILURE");
        run.markWorkerReportValidated();
        run.setCleanupEvidence("{\"status\":\"SUCCESS\"}");
        run.freezeExecutionInput("{\"scope\":{\"database\":\"db\",\"schema\":\"qa\"}}");
        run.setTotalCases(1);
        run.recordCase(TestCaseResultStatus.FAILED);
        var scenario = new TestRunSelectedScenarioEntity(run, "connection.is_valid", "connection");
        scenario.started();
        scenario.finished("FAILED");
        selections.saveAndFlush(scenario);
        run.setName("Historical named run");
        runs.saveAndFlush(run);
        var id = run.getId();
        entityManager.clear();
        mvc.perform(get("/api/runs").param("statusGroup", "COMPLETED"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(get("/api/runs").param("statusGroup", "ABNORMAL"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/runs/{id}", id)).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("FAILED"))
            .andExpect(jsonPath("$.executionOutcome").value("COMPLETED"))
            .andExpect(jsonPath("$.historicalOutcome").value(true))
            .andExpect(jsonPath("$.name").value("Historical named run"));
        assertEquals(TestRunStatus.FAILED, runs.findById(id).orElseThrow().getStatus());
        run = runs.findById(id).orElseThrow();
        run.setCleanupEvidence("{\"status\":\"FAILED\"}");
        runs.saveAndFlush(run);
        mvc.perform(get("/api/runs/{id}", id)).andExpect(status().isOk())
            .andExpect(jsonPath("$.executionOutcome").value("ABNORMAL"));
        mvc.perform(get("/api/runs").param("statusGroup", "COMPLETED"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void groupPredicatePrecedesPaginationAndCountAndPreservesSorting() throws Exception {
        mvc.perform(get("/api/runs").param("statusGroup", "IN_PROGRESS").param("size", "2")
                .param("sort", "createdAt,desc"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(4))
            .andExpect(jsonPath("$.totalPages").value(2))
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.items[0].status").value("GENERATING_REPORT"))
            .andExpect(jsonPath("$.items[1].status").value("RUNNING"));
        mvc.perform(get("/api/runs").param("statusGroup", "IN_PROGRESS").param("size", "2")
                .param("page", "1").param("sort", "createdAt,desc"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(4))
            .andExpect(jsonPath("$.items[0].status").value("PROBING"))
            .andExpect(jsonPath("$.items[1].status").value("PREPARING"));
        mvc.perform(get("/api/runs").param("statusGroup", "ABNORMAL").param("size", "1")
                .param("page", "1").param("sort", "createdAt,asc"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2))
            .andExpect(jsonPath("$.totalPages").value(2))
            .andExpect(jsonPath("$.items[0].status").value("INTERRUPTED"));
        mvc.perform(get("/api/runs").param("statusGroup", "IN_PROGRESS").param("size", "2")
                .param("page", "2"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(4))
            .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void legacyExactStatusAndUnfilteredQueriesRetainTheirBehavior() throws Exception {
        for (TestRunStatus state : TestRunStatus.values()) {
            mvc.perform(get("/api/runs").param("status", state.name()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].status").value(state.name()));
        }
        mvc.perform(get("/api/runs"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(9));
        assertFalse(TestRunStateTransitions.terminal(TestRunStatus.PROBING));
    }

    @Test
    void invalidAndConflictingFiltersReturnProblemResponses() throws Exception {
        for (String value : List.of("invalid", "FAILED", "", "in_progress")) {
            mvc.perform(get("/api/runs").param("statusGroup", value))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_GROUP"));
        }
        for (String state : List.of("FAILED", "")) {
            for (String group : List.of("ABNORMAL", "")) {
                mvc.perform(get("/api/runs").param("status", state).param("statusGroup", group))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("CONFLICTING_STATUS_FILTERS"));
            }
        }
        mvc.perform(get("/api/runs").param("status", "invalid"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        for (String query : List.of("page=-1", "size=0", "sort=unknown,asc", "sort=status,invalid")) {
            mvc.perform(get("/api/runs?statusGroup=IN_PROGRESS&" + query))
                .andExpect(status().isBadRequest());
        }
    }
}
