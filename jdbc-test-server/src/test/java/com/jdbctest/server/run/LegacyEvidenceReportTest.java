package com.jdbctest.server.run;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jdbctest.server.connection.DatabaseConnectionProfileEntity;
import com.jdbctest.server.connection.DatabaseConnectionProfileRepository;
import com.jdbctest.server.driver.DriverSourceType;
import com.jdbctest.server.driver.JdbcDriverPackageEntity;
import com.jdbctest.server.driver.JdbcDriverPackageRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Historical raw evidence in isolated H2, exercised through the real report HTTP branch.
 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:legacy-evidence;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=", "spring.flyway.enabled=false",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "jdbc-test.security.master-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
class LegacyEvidenceReportTest {

    @TempDir
    static Path directory;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("jdbc-test.storage.data-directory", () -> directory.toString());
    }

    @Autowired
    WebApplicationContext context;
    @Autowired
    JdbcDriverPackageRepository drivers;
    @Autowired
    DatabaseConnectionProfileRepository connections;
    @Autowired
    TestRunRepository runs;
    @Autowired
    TestCaseResultRepository results;
    @Autowired
    TestRunSelectedScenarioRepository selected;
    @Autowired
    TestRunService service;
    @Autowired
    TestRunExecutor executor;
    @MockitoBean
    TestRunScheduler scheduler;
    MockMvc mvc;
    final ObjectMapper mapper = new ObjectMapper();
    JdbcDriverPackageEntity driver;
    DatabaseConnectionProfileEntity connection;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        driver = drivers.save(
            new JdbcDriverPackageEntity("fixture", "fixture", "1", "fixture.Driver",
                DriverSourceType.UPLOAD));
        connection = new DatabaseConnectionProfileEntity("fixture", "postgresql", "postgresql",
            "jdbc:fixture:legacy", "fixture");
        connection.setDefaultDriver(driver);
        connection = connections.save(connection);
    }

    private TestRunEntity legacyRun(TestRunStatus status) {
        var run = new TestRunEntity(connection, driver, "CORE", "fixture", "postgresql",
            "postgresql",
            "jdbc:fixture:legacy", "fixture", "fixture", "1", "fixture.Driver", null);
        run.freezeExecutionInput("""
            {"scope":{"schema":"fixture"},"objectPrefix":"fixture_","dataSources":{},
             "adapterExecution":{},"capabilities":{"transactions":true}}
            """);
        run.setStatus(status);
        run.markFinished();
        return runs.save(run);
    }

    private TestCaseResultEntity result(TestRunEntity run, TestCaseResultStatus status,
        String raw) {
        run.setTotalCases(1);
        run.recordCase(status);
        runs.save(run);
        var selection = new TestRunSelectedScenarioEntity(run, "connection.fixture", "connection");
        selection.started();
        selection.finished(status.name());
        selected.save(selection);
        var row = new TestCaseResultEntity(run, "connection.fixture", "connection", "fixture",
            status);
        row.complete(42L, "password=ReviewOnlyLegacyHeadReviewOnlyLegacyTail", null);
        row.setExecutionEvidence(raw);
        return results.save(row);
    }

    private JsonNode report(UUID id) throws Exception {
        assertFalse(Files.exists(directory.resolve("runs").resolve(id.toString())
                .resolve("report/report.json")),
            "must exercise database reconstruction, not file fallback");
        return mapper.readTree(mvc.perform(get("/api/runs/{id}/report.json", id))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private static void assertSafe(String text) {
        assertFalse(text.contains("ReviewOnlyLegacyHead"), text);
        assertFalse(text.contains("ReviewOnlyLegacyTail"), text);
    }

    private static void controlledJdbcFailure() {
        throw new NullPointerException("controlled failure password=delivery-password\n"
            + "masterKey=delivery-key\njdbc:oracle:thin:user/delivery-url-secret@host/db");
    }

    @Test
    void workerGeneratedStackSurvivesEventPersistenceRestAndDownload() throws Exception {
        Throwable failure = assertThrows(NullPointerException.class,
            LegacyEvidenceReportTest::controlledJdbcFailure);
        var next = new java.sql.SQLException("next exception", "HY001", 72);
        var sql = new java.sql.SQLException("suppressed", "HY000", 71);
        sql.setNextException(next);
        failure.addSuppressed(sql);
        Class<?> evidence = Class.forName("com.jdbctest.worker.WorkerExecutionEvidence");
        java.util.Map<String, Object> row = org.springframework.test.util.ReflectionTestUtils
            .invokeMethod(evidence, "failure", failure);
        assertNotNull(row);
        row.putAll(org.springframework.test.util.ReflectionTestUtils.invokeMethod(evidence,
            "conclusion", org.junit.platform.engine.TestExecutionResult.Status.FAILED,
            failure, "EXECUTION"));
        String trace = (String)row.get("stackTrace");
        assertTrue(trace.contains("LegacyEvidenceReportTest.controlledJdbcFailure("));
        assertTrue(trace.contains("NEXT_EXCEPTION: java.sql.SQLException"));
        for (String secret : new String[] {"delivery-password", "delivery-key",
            "delivery-url-secret"}) {
            assertFalse(trace.contains(secret));
        }
        // Exercise the actual varchar(12000) boundary, not just short happy-path persistence.
        String marker = "\n... [TRUNCATED]\n";
        trace += "x".repeat(12000 - trace.length() - marker.length()) + marker;
        row.put("stackTrace", trace);
        var run = legacyRun(TestRunStatus.RUNNING);
        run.setTotalCases(1);
        runs.save(run);
        selected.save(new TestRunSelectedScenarioEntity(run, "connection.fixture", "connection"));
        row.putAll(java.util.Map.of("type", "CASE_FINISHED", "runId", run.getId().toString(),
            "scenarioId", "connection.fixture", "moduleId", "connection", "status", "FAILED",
            "phase", "EXECUTION", "durationMs", 42));
        for (var event : java.util.List.of(
            java.util.Map.of("type", "RUN_STARTED", "runId", run.getId().toString(),
                "protocolVersion", 2),
            java.util.Map.of("type", "CASE_STARTED", "runId", run.getId().toString(),
                "scenarioId", "connection.fixture", "moduleId", "connection"), row)) {
            org.springframework.test.util.ReflectionTestUtils.invokeMethod(executor, "handle",
                run.getId(), mapper.writeValueAsString(event));
        }
        var stored = results.findByRun_Id(run.getId()).getFirst();
        assertEquals(trace, stored.getStackTrace());
        assertEquals(TestCaseResultStatus.FAILED, stored.getStatus());
        assertEquals("NOT_EVALUATED", mapper.readTree(stored.getExecutionEvidence())
            .path("conclusion").asString());
        run = runs.findById(run.getId()).orElseThrow();
        run.setStatus(TestRunStatus.SUCCEEDED);
        runs.save(run);
        var listed = mapper.readTree(mvc.perform(get("/api/runs/{id}/results", run.getId()))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
            .path("items").get(0);
        assertEquals("FAILED", listed.path("status").asString());
        assertEquals(trace, listed.path("stackTrace").asString());
        var downloaded = report(run.getId()).path("scenario_results").path("connection.fixture");
        assertEquals(trace, downloaded.path("stackTrace").asString());
        assertEquals("NOT_EVALUATED", downloaded.path("conclusion").asString());
        assertEquals(mapper.valueToTree(row.get("causes")), downloaded.path("causes"));
        assertFalse(downloaded.has("contractConforms"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.NullAndEmptySource
    @org.junit.jupiter.params.provider.ValueSource(strings = {"invalid JSON", "[]", "null", "{\"message\":\"legacy\"}"})
    void missingOrUnreadableCaseEvidenceDoesNotBreakHistoricalDetailsOrReport(String raw) throws Exception {
        var run = legacyRun(TestRunStatus.FAILED);
        var row = result(run, TestCaseResultStatus.FAILED, raw);
        var page = service.results(run.getId(), 0, 10, null, null, null);
        assertEquals(TestCaseResultStatus.FAILED, page.items().getFirst().status());
        assertNull(page.items().getFirst().stackTrace());
        var downloaded = report(run.getId()).path("scenario_results").path("connection.fixture");
        assertFalse(downloaded.has("stackTrace"));
        if (raw != null && !raw.equals("{\"message\":\"legacy\"}")) {
            assertEquals("LEGACY_EVIDENCE_UNREADABLE", downloaded.path("evidenceReadError").asString());
        }
        if (raw != null && raw.contains("legacy")) {
            assertEquals("legacy", downloaded.path("message").asString());
        }
        assertEquals(raw, results.findById(row.getId()).orElseThrow().getExecutionEvidence());
        assertEquals(TestRunStatus.FAILED, runs.findById(run.getId()).orElseThrow().getStatus());
    }

    @Test
    void retiredSqlServerHistoryStillListsAndDownloadsOriginalConclusions() throws Exception {
        var run = legacyRun(TestRunStatus.FAILED);
        org.springframework.test.util.ReflectionTestUtils.setField(run, "databaseTypeSnapshot", "sqlserver");
        org.springframework.test.util.ReflectionTestUtils.setField(run, "adapterIdSnapshot", "sqlserver");
        run.freezeExecutionInput("{\"adapterExecution\":{\"id\":\"sqlserver\",\"dialect\":\"sqlserver\"},\"scope\":{}}");
        String original = "{\"conclusion\":\"CONTRACT_VIOLATION\",\"contractInvoked\":true}";
        var row = result(run, TestCaseResultStatus.FAILED, original);
        var listed = service.list(0, 100, null, null).items().stream()
            .filter(item -> item.id().equals(run.getId())).findFirst().orElseThrow();
        assertEquals("sqlserver", listed.connection().adapterId());
        assertEquals(TestRunStatus.FAILED, listed.status());
        mvc.perform(get("/api/runs/{id}", run.getId())).andExpect(status().isOk());
        mvc.perform(get("/api/runs/{id}/report", run.getId())).andExpect(status().isOk());
        assertEquals("CONTRACT_VIOLATION", report(run.getId()).path("scenario_results")
            .path("connection.fixture").path("conclusion").asString());
        assertEquals(original, results.findById(row.getId()).orElseThrow().getExecutionEvidence());
        assertEquals(TestRunStatus.FAILED, runs.findById(run.getId()).orElseThrow().getStatus());
    }

    @Test
    void detailSummaryAndDownloadRetainDistinctContractOutcomes() throws Exception {
        for (String conclusion : new String[] {"CONTRACT_VIOLATION", "UNSUPPORTED_ALLOWED",
            "PREREQUISITE_UNMET", "PLATFORM_ERROR", "NOT_EVALUATED"}) {
            var run = legacyRun(TestRunStatus.FAILED);
            boolean suite = "NOT_EVALUATED".equals(conclusion);
            String raw = mapper.writeValueAsString(java.util.Map.of(
                "conclusion", conclusion, "requirementScope", suite ? "SUITE" : "",
                "status", "UNSUPPORTED_ALLOWED".equals(conclusion) ? "ABORTED" : "FAILED"));
            var row = result(run, "UNSUPPORTED_ALLOWED".equals(conclusion)
                ? TestCaseResultStatus.ABORTED : TestCaseResultStatus.FAILED, raw);
            var summary = mapper.readTree(mvc.perform(get("/api/runs/{id}/report", run.getId()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            assertEquals(1, summary.path("summary").path("contractSummary")
                .path(conclusion).asInt());
            assertEquals(suite ? 1 : 0, summary.path("summary").path("contractSummary")
                .path("SUITE_REQUIREMENT_UNMET").asInt());
            assertEquals(conclusion, summary.path("failures").get(0)
                .path("executionEvidence").path("conclusion").asString());
            var downloaded = report(run.getId()).path("scenario_results").path("connection.fixture");
            assertEquals(conclusion, downloaded.path("conclusion").asString());
            assertEquals(suite ? "SUITE" : "", downloaded.path("requirementScope").asString());
            assertEquals(raw, results.findById(row.getId()).orElseThrow().getExecutionEvidence());
        }
    }

    @Test
    void historicalV2ReportProjectsCompleteResponseAndLedgerWithoutChangingStoredEvidence()
        throws Exception {
        var run = legacyRun(TestRunStatus.FAILED);
        String raw = """
            {"type":"CASE_FINISHED","timestamp":"historical","runId":"obsolete",
             "scenarioId":"connection.fixture","status":"FAILED","durationMs":42,
             "message":"password=ReviewOnlyLegacyHeadReviewOnlyLegacyTail",
             "causes":[{"token":"ReviewOnlyLegacyHeadReviewOnlyLegacyTail","optional":null}],
             "passwordRequired":true}
            """;
        var row = result(run, TestCaseResultStatus.FAILED, raw);
        Path workspace = directory.resolve("runs").resolve(run.getId().toString());
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("object-ledger.yaml"), """
            objects:
              - message: password=ReviewOnlyLegacyHeadReviewOnlyLegacyTail
                trustStorePassword: ReviewOnlyLegacyHeadReviewOnlyLegacyTail
                attempts: 2
                owned: true
            """);
        var json = report(run.getId());
        assertSafe(json.toString());
        assertEquals("PERSISTED_WORKER_EVENTS", json.path("source").asString());
        assertEquals(run.getId().toString(), json.path("runId").asString());
        var event = json.path("scenario_results").path("connection.fixture");
        assertEquals("FAILED", event.path("status").asString());
        assertEquals(42, event.path("durationMs").asInt());
        assertTrue(event.path("passwordRequired").asBoolean());
        assertTrue(event.path("causes").get(0).path("optional").isNull());
        assertFalse(event.has("type"));
        assertFalse(event.has("timestamp"));
        assertFalse(event.has("runId"));
        var object = json.path("objectLedger").path("objects").get(0);
        assertEquals(2, object.path("attempts").asInt());
        assertTrue(object.path("owned").asBoolean());
        assertSafe(mapper.writeValueAsString(service.evidenceReport(run.getId())));
        for (String suffix : new String[] {"/results", "/report", ""}) {
            String body = mvc.perform(get("/api/runs/" + run.getId() + suffix))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertSafe(body);
        }
        var list = mapper.readTree(mvc.perform(get("/api/runs/{id}/results", run.getId()))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        var listed = (tools.jackson.databind.node.ObjectNode) list.path("items").get(0)
            .path("executionEvidence").deepCopy();
        listed.remove("type");
        listed.remove("timestamp");
        listed.remove("runId");
        assertEquals(event, listed);
        assertEquals(raw, results.findById(row.getId()).orElseThrow().getExecutionEvidence());
        assertTrue(Files.readString(workspace.resolve("object-ledger.yaml"))
            .contains("ReviewOnlyLegacyHead"));
    }

    @Test
    void oldSuccessRetainsCompletedWhileCleanupRemainsUnknown() throws Exception {
        var run = legacyRun(TestRunStatus.SUCCEEDED);
        result(run, TestCaseResultStatus.PASSED, "{}");
        run.markWorkerReportValidated();
        assertReason(run, "COMPLETED");
    }

    @Test
    void oldTestFailureRetainsTestFailureWhileCleanupRemainsUnknown() throws Exception {
        var run = legacyRun(TestRunStatus.FAILED);
        result(run, TestCaseResultStatus.FAILED, "{}");
        run.markWorkerReportValidated();
        run.setFailureReason("WORKER_EXECUTION_FAILED");
        assertReason(run, "TEST_FAILURE");
    }

    @Test
    void oldContainerFailureRetainsContainerFailureWhileCleanupRemainsUnknown() throws Exception {
        var run = legacyRun(TestRunStatus.FAILED);
        run.markWorkerReportValidated();
        run.setExecutionIssues("[{\"level\":\"CONTAINER\",\"message\":\"fixture failure\"}]");
        run.setFailureReason("WORKER_EXECUTION_FAILED");
        assertReason(run, "CONTAINER_FAILURE");
    }

    @Test
    void oldNoTestsRetainsNoTestsExecutedWhileCleanupRemainsUnknown() throws Exception {
        var run = legacyRun(TestRunStatus.FAILED);
        run.markWorkerReportValidated();
        run.setFailureReason("WORKER_EXECUTION_FAILED");
        assertReason(run, "NO_TESTS_EXECUTED");
    }

    @Test
    void oldCancelledTimeoutAndInterruptedRetainServerReasonsAndUnknownCleanup() throws Exception {
        for (boolean validated : new boolean[] {false, true}) {
            for (var status : new TestRunStatus[] {TestRunStatus.CANCELLED, TestRunStatus.FAILED,
                TestRunStatus.INTERRUPTED}) {
                var run = legacyRun(status);
                String reason = status == TestRunStatus.CANCELLED ? "RUN_CANCELLED"
                    : status == TestRunStatus.FAILED ? "RUN_TIMEOUT" : "SERVER_RESTARTED";
                run.setFailureReason(reason);
                if (validated) {
                    run.markWorkerReportValidated();
                }
                assertReason(run, reason);
            }
        }
    }

    @Test
    void newCleanupFailureKeepsPersistedWorkerReasonAheadOfGenericServerFailure() throws Exception {
        var run = legacyRun(TestRunStatus.FAILED);
        result(run, TestCaseResultStatus.PASSED, "{}");
        run.markWorkerReportValidated();
        run.setWorkerTerminationReason("CLEANUP_FAILURE");
        run.setFailureReason("WORKER_EXECUTION_FAILED");
        run.setCleanupEvidence("{\"status\":\"FAILED\",\"phase\":\"DROP\"}");
        runs.save(run);
        var json = report(run.getId());
        assertEquals("CLEANUP_FAILURE", json.path("terminationReason").asString());
        assertEquals("FAILED", json.path("cleanupEvidence").path("status").asString());
        assertEquals(1, json.path("summary").path("passed").asInt());
        assertEquals(0, json.path("summary").path("failed").asInt());
    }

    private void assertReason(TestRunEntity run, String expected) throws Exception {
        assertNull(run.getWorkerTerminationReason(), "legacy column must remain absent");
        runs.save(run);
        var json = report(run.getId());
        assertEquals(expected, json.path("terminationReason").asString());
        assertEquals("UNKNOWN", json.path("cleanupEvidence").path("status").asString());
        assertEquals("NOT_RECORDED", json.path("cleanupEvidence").path("phase").asString());
        var stored = runs.findById(run.getId()).orElseThrow();
        assertNull(stored.getWorkerTerminationReason());
        assertNull(stored.getCleanupEvidence());
    }
}
