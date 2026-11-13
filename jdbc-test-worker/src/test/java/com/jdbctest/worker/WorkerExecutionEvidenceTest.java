package com.jdbctest.worker;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdbctest.catalog.*;
import com.jdbctest.extension.ExecutionPrerequisiteException;
import com.jdbctest.extension.JdbcContracts;
import java.io.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.launcher.core.*;

class WorkerExecutionEvidenceTest {

    static class ControlledFailure {

        @Test
        void first() {
            try {
                failAtJdbcCall();
            } catch (NullPointerException error) {
                throw new IllegalStateException("controlled wrapper", error);
            }
        }

        private static void failAtJdbcCall() {
            throw new NullPointerException("controlled driver failure");
        }

        @Test
        void second() {
        }
    }

    @Test
    void actualFailureFramesSurviveTheEventAndReportProjectionWithoutChangingClassification(
        @org.junit.jupiter.api.io.TempDir java.nio.file.Path workspace)
        throws Exception {
        var output = new ByteArrayOutputStream();
        var evidence = execute(ControlledFailure.class, output);
        var row = evidence.results().stream()
            .filter(item -> "fixture.first".equals(item.get("scenarioId")))
            .findFirst().orElseThrow();
        String trace = (String)row.get("stackTrace");
        assertTrue(trace.contains("ROOT: java.lang.IllegalStateException: controlled wrapper"));
        assertTrue(trace.contains("CAUSE: java.lang.NullPointerException"));
        assertTrue(trace.contains("ControlledFailure.failAtJdbcCall(WorkerExecutionEvidenceTest.java:"));
        assertEquals("FAILED", row.get("status"));
        assertEquals("EXECUTION", row.get("phase"));
        assertEquals("NOT_EVALUATED", row.get("conclusion"));
        assertFalse(row.containsKey("contractConforms"));
        var mapper = new ObjectMapper();
        var event = output.toString(java.nio.charset.StandardCharsets.UTF_8).lines()
            .map(line -> {
                try {
                    return mapper.readTree(line);
                } catch (IOException error) {
                    throw new UncheckedIOException(error);
                }
            }).filter(item -> "CASE_FINISHED".equals(item.path("type").asText())
                && "fixture.first".equals(item.path("scenarioId").asText()))
            .findFirst().orElseThrow();
        var request = mapper.readValue("""
            {"runId":"fixture","connection":{"adapterId":"postgresql"},"driver":{}}
            """, WorkerRunRequest.class);
        var report = mapper.readTree(java.nio.file.Files.readString(
            WorkerReportWriter.write(workspace, request, List.of(), evidence)));
        assertEquals(trace, event.path("stackTrace").asText());
        assertEquals(trace, report.path("scenario_results").path("fixture.first")
            .path("stackTrace").asText());
    }

    @Test
    void stackFramesAndEveryExceptionBranchAreRedactedBeforeVisibleTruncation() {
        String secret = "raw-fixture-secret";
        var failure = new java.sql.SQLException("x".repeat(550) + secret
            + " password=assigned-secret\nmasterKey=key-secret\n"
            + "jdbc:oracle:thin:user/url-secret@host:1521/db", "HY000", 71);
        var next = new java.sql.SQLException("next", "HY001", 72);
        var suppressed = new IllegalStateException("suppressed token=token-secret");
        failure.setNextException(next);
        failure.addSuppressed(suppressed);
        next.initCause(failure);
        failure.setStackTrace(new StackTraceElement[] {
            new StackTraceElement("fixture." + secret, "invoke", "Failure.java", 42)});
        CredentialSanitizer.password(secret);
        try {
            var evidence = WorkerExecutionEvidence.failure(failure);
            String trace = (String)evidence.get("stackTrace");
            assertTrue(trace.contains("Failure.java:42"));
            assertTrue(trace.contains("NEXT_EXCEPTION: java.sql.SQLException: next"));
            assertTrue(trace.contains("SUPPRESSED: java.lang.IllegalStateException"));
            assertTrue(trace.contains("[TRUNCATED"));
            for (String value : List.of(secret, "assigned-secret", "key-secret", "url-secret",
                "token-secret")) {
                assertFalse(trace.contains(value));
                assertFalse(evidence.toString().contains(value));
            }
            assertEquals(3, ((List<?>)evidence.get("causes")).size());
        } finally {
            CredentialSanitizer.password(null);
        }
    }

    @Test
    void graphAndFrameBudgetsKeepEachVisitedCauseLocationIncludingDeepFailures() {
        Throwable error = new NullPointerException("deep failure");
        for (int depth = 0; depth < 12; depth++) {
            var wrapper = new IllegalStateException("wrapper-" + depth, error);
            var frames = new StackTraceElement[100];
            Arrays.fill(frames, new StackTraceElement("fixture.Layer" + depth,
                "invoke", "Layer.java", depth + 1));
            wrapper.setStackTrace(frames);
            error = wrapper;
        }
        String trace = (String)WorkerExecutionEvidence.failure(error).get("stackTrace");
        assertTrue(trace.length() <= 12000);
        assertTrue(trace.contains("[TRUNCATED"));
        assertTrue(trace.contains("fixture.Layer11.invoke(Layer.java:12)"));
        assertTrue(trace.contains("fixture.Layer2.invoke(Layer.java:3)"));
        assertFalse(trace.contains("fixture.Layer1.invoke"));
    }

    static class UnverifiedApplicability {

        @Test
        void first() {
            throw new JdbcContracts.ContractApplicabilityAssertionError(
                "JDBC 4.1 baseline unverified", new AssertionError("null Logger"));
        }

        @Test
        void second() {
            throw new AssertionError("reviewed API assertion");
        }
    }

    @Test
    void unverifiedApplicabilityRemainsFailedInEventsAndReportWithItsOriginalAssertion()
        throws Exception {
        var evidence = execute(UnverifiedApplicability.class);
        var row = evidence.results().stream()
            .filter(item -> "fixture.first".equals(item.get("scenarioId")))
            .findFirst().orElseThrow();
        assertEquals("FAILED", row.get("status"));
        assertEquals("EXECUTION", row.get("phase"));
        assertEquals(true, row.get("contractInvoked"));
        assertEquals("NOT_EVALUATED", row.get("conclusion"));
        assertFalse(row.containsKey("contractConforms"));
        assertFalse(row.containsKey("requirementScope"));
        String json = new ObjectMapper().writeValueAsString(row);
        assertTrue(json.contains("null Logger"));
        assertTrue(json.contains("JDBC 4.1 baseline unverified"));
        assertEquals(2, evidence.results().stream()
            .filter(item -> "FAILED".equals(item.get("status"))).count());
        assertTrue(evidence.results().stream()
            .anyMatch(item -> "CONTRACT_VIOLATION".equals(item.get("conclusion"))));
    }

    @Test
    void failureRetainsSqlNextAndSuppressedBranchesWithCyclesAndRedaction() throws Exception {
        var cancel = new java.sql.SQLException("cancel secret-fixture", "HY000", 77);
        var failure = new java.util.concurrent.ExecutionException(cancel);
        var execution = new java.sql.SQLException("execution", "HY008", 78);
        failure.addSuppressed(execution);
        execution.setNextException(new java.sql.SQLException("next", "08006", 79));
        cancel.initCause(failure);
        CredentialSanitizer.password("secret-fixture");
        try {
            String json = new ObjectMapper().writeValueAsString(
                WorkerExecutionEvidence.failure(failure));
            assertFalse(json.contains("secret-fixture"));
            assertTrue(json.contains("SUPPRESSED"));
            assertTrue(json.contains("NEXT_EXCEPTION"));
            for (String state : List.of("HY000", "HY008", "08006")) {
                assertTrue(json.contains(state));
            }
            var branches = (List<?>) WorkerExecutionEvidence.failure(failure).get("causes");
            assertEquals(4, branches.size());
        } finally {
            CredentialSanitizer.password(null);
        }
    }

    @Test
    void diagnosticGraphBoundsCountsAndLengthsWithoutReplacingPrimaryOrLeakingSecrets() {
        var primary = new java.sql.SQLException("x".repeat(1995) + "secret-fixture".repeat(1000),
            "HY008", 78);
        var secondary = new java.sql.SQLException("secondary", "HY000", 79);
        primary.setNextException(secondary);
        secondary.setNextException(primary);
        secondary.initCause(primary);
        primary.addSuppressed(secondary);
        for (int index = 0; index < 100; index++) {
            primary.addSuppressed(new IllegalStateException("y".repeat(5000)));
        }
        CredentialSanitizer.password("secret-fixture");
        try {
            var evidence = WorkerExecutionEvidence.failure(primary);
            assertEquals("HY008", evidence.get("sqlState"));
            assertEquals(78, evidence.get("vendorCode"));
            assertEquals(primary.getClass().getName(), evidence.get("errorType"));
            assertTrue(((String)evidence.get("message")).length() <= 2000);
            assertFalse(evidence.toString().contains("secret-fixture"));
            var branches = (List<?>)evidence.get("causes");
            assertEquals(10, branches.size());
            assertEquals(1, branches.stream().filter(row ->
                "secondary".equals(((Map<?, ?>)row).get("message"))).count());
            for (Object row : branches) {
                assertTrue(((String)((Map<?, ?>)row).get("message")).length() <= 2000);
            }
            var assertion = new AssertionError("primary assertion");
            assertion.addSuppressed(secondary);
            var assertionEvidence = WorkerExecutionEvidence.failure(assertion);
            assertFalse(assertionEvidence.containsKey("sqlState"));
            assertEquals("NOT_EVALUATED", WorkerExecutionEvidence.conclusion(
                TestExecutionResult.Status.FAILED, assertion, "PREPARATION").get("conclusion"));
            assertEquals("CONTRACT_VIOLATION", WorkerExecutionEvidence.conclusion(
                TestExecutionResult.Status.FAILED, assertion, "EXECUTION").get("conclusion"));
        } finally {
            CredentialSanitizer.password(null);
        }
    }

    @Test
    void supplementalExceptionsCannotSupplyPrimarySqlStateOrChangePhaseClassification() {
        var original = new java.sql.SQLException("primary", "HY008", 78);
        original.addSuppressed(new ExecutionPrerequisiteException("secondary prerequisite", null));
        original.addSuppressed(new java.util.concurrent.TimeoutException("secondary timeout"));
        var evidence = WorkerExecutionEvidence.failure(original);
        assertEquals("HY008", evidence.get("sqlState"));
        assertEquals(78, evidence.get("vendorCode"));
        assertEquals("NOT_EVALUATED", WorkerExecutionEvidence.conclusion(
            TestExecutionResult.Status.FAILED, original, "EXECUTION").get("conclusion"));
        var longState = new java.sql.SQLException("primary", "z".repeat(5000));
        assertEquals(2000, ((String)WorkerExecutionEvidence.failure(longState).get("sqlState")).length());
    }

    static class BrokenSetup {

        @BeforeAll
        static void setup() {
            throw new IllegalStateException("fixture setup failure");
        }

        @Test
        void first() {
            fail("must not execute");
        }

        @Test
        void second() {
            fail("must not execute");
        }
    }

    static class Passed {

        @Test
        void first() {
        }

        @Test
        void second() {
        }
    }

    static class MissingConnection {

        @Test
        void first(org.junit.jupiter.api.TestReporter reporter) {
            reporter.publishEntry("jdbc.phase", "EXECUTION");
            throw new org.junit.jupiter.api.extension.ParameterResolutionException(
                "JUnit connection parameter wrapper", new com.jdbctest.extension.TargetConnectionException(
                    new java.sql.SQLException("fixture authentication failure", "28000", 8)));
        }

        @Test
        void second() {
        }
    }

    static class CleanupFailure {

        @Test
        void first(org.junit.jupiter.api.TestReporter reporter) {
            reporter.publishEntry("jdbc.phaseDurationMs.PREPARATION", "2");
            reporter.publishEntry("jdbc.phaseDurationMs.EXECUTION", "3");
        }

        @AfterEach
        void cleanup(org.junit.jupiter.api.TestReporter reporter) throws java.sql.SQLException {
            reporter.publishEntry("jdbc.phase", "CLEANUP");
            reporter.publishEntry("jdbc.phaseDurationMs.CLEANUP", "4");
            throw new java.sql.SQLException("fixture cleanup failure", "08006");
        }
    }

    static class BodyConnectionFailure {

        @Test
        void first() throws java.sql.SQLException {
            throw new java.sql.SQLException("fixture tested getConnection failed", "28000", 8);
        }
    }

    @Disabled("fixture explicit container skip")
    static class SkippedContainer {

        @Test
        void first() {
            fail("must not execute");
        }

        @Test
        void second() {
            fail("must not execute");
        }
    }

    @TestMethodOrder(MethodOrderer.MethodName.class)
    static class Mixed {

        @Test
        void first() {
        }

        @Test
        void second() {
            throw new AssertionError("fixture assertion failure");
        }
    }

    WorkerExecutionEvidence execute(Class<?> fixture) {
        return execute(fixture, new ByteArrayOutputStream());
    }

    WorkerExecutionEvidence execute(Class<?> fixture, ByteArrayOutputStream output) {
        var module = new TestModule("fixture", "Fixture", "Evidence regression");
        var selected = List.of("first", "second").stream().map(
            method -> new TestCaseDescriptor("fixture." + method, module, TestProfile.CORE, method,
                fixture.getName(), method, List.of())).toList();
        var evidence = new WorkerExecutionEvidence("fixture", selected,
            new WorkerEventWriter(new ObjectMapper(), new PrintStream(output)));
        var request = LauncherDiscoveryRequestBuilder.request()
            .selectors(DiscoverySelectors.selectClass(fixture)).build();
        LauncherFactory.create().execute(request, evidence);
        return evidence;
    }

    @Test
    void suiteRequirementFailureDoesNotClaimJdbcViolationOrSuccess() {
        var result = WorkerExecutionEvidence.conclusion(TestExecutionResult.Status.FAILED,
            new JdbcContracts.SuiteRequirementAssertionError("REF metadata required by suite"));
        assertEquals("NOT_EVALUATED", result.get("conclusion"));
        assertEquals("SUITE", result.get("requirementScope"));
        assertFalse(result.containsKey("contractConforms"));
        var exactReadback = new JdbcContracts.SuiteRequirementAssertionError(
            "materialized LOB does not write through", new AssertionError("ABCDE != AXYDE"));
        var retained = WorkerExecutionEvidence.conclusion(
            TestExecutionResult.Status.FAILED, exactReadback);
        assertEquals("NOT_EVALUATED", retained.get("conclusion"));
        assertEquals("SUITE", retained.get("requirementScope"));
        assertFalse(retained.containsKey("contractConforms"));
        assertEquals("CONTRACT_VIOLATION", WorkerExecutionEvidence.conclusion(
            TestExecutionResult.Status.FAILED, new AssertionError("reviewed API assertion"))
            .get("conclusion"));
    }

    @Test
    void conclusionsSeparateOptionalUnsupportedRequiredUnsupportedFixturesAndTimeouts() {
        var unsupported = new java.sql.SQLFeatureNotSupportedException(
            "fixture unsupported", "0A000", 7);
        var unknown = WorkerExecutionEvidence.conclusion(TestExecutionResult.Status.FAILED, unsupported);
        assertEquals("NOT_EVALUATED", unknown.get("conclusion"));
        assertFalse(unknown.containsKey("contractConforms"));
        Throwable required = assertThrows(JdbcContracts.UnsupportedRequiredException.class,
            () -> JdbcContracts.required("Connection.getAutoCommit", () -> { throw unsupported; }));
        assertEquals("UNSUPPORTED_REQUIRED", WorkerExecutionEvidence.conclusion(
            TestExecutionResult.Status.FAILED, required).get("conclusion"));
        Throwable optional = assertThrows(JdbcContracts.UnsupportedAllowedException.class,
            () -> JdbcContracts.optional("Connection.createBlob", () -> {
                throw unsupported;
            }));
        var allowed = WorkerExecutionEvidence.conclusion(
            TestExecutionResult.Status.ABORTED, optional);
        assertEquals("UNSUPPORTED_ALLOWED", allowed.get("conclusion"));
        assertEquals(true, allowed.get("contractConforms"));
        var missing = WorkerExecutionEvidence.conclusion(TestExecutionResult.Status.ABORTED,
            new ExecutionPrerequisiteException("FIXTURE_UNAVAILABLE: fixture.sql", unsupported));
        assertEquals("PREREQUISITE_UNMET", missing.get("conclusion"));
        assertFalse(missing.containsKey("contractConforms"));
        assertEquals("TIMEOUT", WorkerExecutionEvidence.conclusion(TestExecutionResult.Status.FAILED,
            new java.util.concurrent.TimeoutException()).get("conclusion"));
        assertEquals("NOT_EVALUATED", WorkerExecutionEvidence.conclusion(
            TestExecutionResult.Status.FAILED,
            new java.sql.SQLException("fixture syntax", "42000")).get("conclusion"));
    }

    @Test
    void preparationSqlAndWrappedDependencyFailuresDoNotBecomeDriverFailures() {
        var sql = new java.sql.SQLFeatureNotSupportedException("fixture connection unavailable");
        var preparation = WorkerExecutionEvidence.conclusion(TestExecutionResult.Status.FAILED,
            sql, "PREPARATION");
        assertEquals("NOT_EVALUATED", preparation.get("conclusion"));
        assertFalse(preparation.containsKey("contractConforms"));
        var blocked = new IllegalStateException("JUnit wrapper",
            new ExecutionPrerequisiteException("FIXTURE_BLOCKED: fixture.sql", sql,
                "fixture.sql", "fixture.Setup#first"));
        assertEquals("BLOCKED_PREREQUISITE", WorkerExecutionEvidence.conclusion(
            TestExecutionResult.Status.ABORTED, blocked).get("conclusion"));
        assertEquals("PLATFORM_ERROR", WorkerExecutionEvidence.conclusion(
            TestExecutionResult.Status.FAILED, new IllegalStateException("platform setup"),
            "PREPARATION").get("conclusion"));
        var assertion = WorkerExecutionEvidence.conclusion(TestExecutionResult.Status.FAILED,
            new AssertionError("fixture setup expectation"), "PREPARATION");
        assertEquals("NOT_EVALUATED", assertion.get("conclusion"));
        assertFalse(assertion.containsKey("contractConforms"));
    }

    @Test
    void unclassifiedExecutionErrorsDoNotProveAPlatformDefectOrConformance() {
        var driverError = new NullPointerException("driver return-parameter state");
        driverError.setStackTrace(new StackTraceElement[] {
            new StackTraceElement("oracle.jdbc.driver.OraclePreparedStatement", "addBatch",
                "OraclePreparedStatement.java", 1)
        });
        for (Throwable error : new Throwable[] {driverError,
            new NullPointerException("suite state"), new IllegalStateException("unknown origin")}) {
            var evidence = WorkerExecutionEvidence.conclusion(TestExecutionResult.Status.FAILED,
                error, "EXECUTION");
            assertEquals("NOT_EVALUATED", evidence.get("conclusion"));
            assertFalse(evidence.containsKey("contractConforms"));
        }
    }

    @Test
    void connectionParameterFailureRemainsPreparationEvenAfterExecutionCallback() {
        var row = execute(MissingConnection.class).results().stream()
            .filter(item -> item.get("scenarioId").equals("fixture.first")).findFirst().orElseThrow();
        assertEquals("PREPARATION", row.get("phase"));
        assertEquals(false, row.get("contractInvoked"));
        assertEquals("CONNECTION_FAILURE", row.get("conclusion"));
        assertEquals("28000", row.get("sqlState"));
        assertEquals(8, row.get("vendorCode"));
        assertFalse(row.containsKey("contractConforms"));
    }

    @Test
    void cleanupFailurePreservesInvocationAndStageDurationsWithoutClaimingContractFailure() {
        var row = execute(CleanupFailure.class).results().getFirst();
        assertEquals("CLEANUP", row.get("phase"));
        assertEquals(true, row.get("contractInvoked"));
        assertEquals("NOT_EVALUATED", row.get("conclusion"));
        assertEquals(Map.of("PREPARATION", 2L, "EXECUTION", 3L, "CLEANUP", 4L),
            row.get("phaseDurationsMs"));
        assertFalse(row.containsKey("contractConforms"));
    }

    @Test
    void unscopedSqlFailureRetainsFailedInvocationWithoutAttributingContractFault() {
        var row = execute(BodyConnectionFailure.class).results().getFirst();
        assertEquals("EXECUTION", row.get("phase"));
        assertEquals(true, row.get("contractInvoked"));
        assertEquals("FAILED", row.get("status"));
        assertEquals("NOT_EVALUATED", row.get("conclusion"));
        assertFalse(row.containsKey("contractConforms"));
        assertEquals("28000", row.get("sqlState"));
    }

    @Test
    void cleanupChangesOnlyRunConclusionAndPreservesPrimaryFailure() {
        var evidence = execute(Passed.class);
        assertEquals("CLEANUP_FAILURE", evidence.terminationReason());
        evidence.recordCleanup(Map.of("status", "FAILED"));
        assertEquals("CLEANUP_FAILURE", evidence.terminationReason());
        assertEquals(2,
            evidence.results().stream().filter(r -> "PASSED".equals(r.get("status"))).count());
        evidence.recordCleanup(Map.of("status", "SUCCESS"));
        assertEquals("COMPLETED", evidence.terminationReason());
        var failed = execute(Mixed.class);
        failed.recordCleanup(Map.of("status", "FAILED"));
        assertEquals("TEST_FAILURE", failed.terminationReason());
    }

    @Test
    void wrappedSqlFailurePreservesOriginalCauseAndVendorEvidence() {
        var evidence = WorkerExecutionEvidence.failure(
            new IllegalStateException("fixture initialization",
                new java.sql.SQLException("fixture SQL failure", "42000", 777)));
        assertEquals("42000", evidence.get("sqlState"));
        assertEquals(777, evidence.get("vendorCode"));
        var causes = (List<Map<String, Object>>) evidence.get("causes");
        assertEquals("fixture SQL failure", causes.get(1).get("message"));
    }

    @Test
    void beforeAllFailureRetainsContainerFailureWithoutInventedChildResults() {
        var evidence = execute(BrokenSetup.class);
        assertTrue(evidence.results().isEmpty());
        assertTrue(evidence.started().isEmpty());
        assertEquals(1, evidence.issues().size());
        assertEquals("CONTAINER_FAILURE", evidence.terminationReason());
        assertEquals("fixture setup failure", evidence.issues().getFirst().get("message"));
    }

    @Test
    void explicitContainerSkipMapsToSelectedChildrenWithSource() {
        var evidence = execute(SkippedContainer.class);
        assertEquals(2, evidence.results().size());
        assertTrue(evidence.started().isEmpty());
        assertTrue(evidence.results().stream()
            .allMatch(row -> row.get("status").equals("SKIPPED") && row.containsKey("skipSource")));
        assertEquals("NO_TESTS_EXECUTED", evidence.terminationReason());
    }

    @Test
    void realFailureIsOneResultAndNotContainerFailure() {
        var evidence = execute(Mixed.class);
        assertEquals(2, evidence.started().size());
        assertTrue(evidence.issues().isEmpty());
        assertEquals(List.of("PASSED", "FAILED"),
            evidence.results().stream().map(row -> row.get("status")).toList());
        assertEquals("TEST_FAILURE", evidence.terminationReason());
    }
}
