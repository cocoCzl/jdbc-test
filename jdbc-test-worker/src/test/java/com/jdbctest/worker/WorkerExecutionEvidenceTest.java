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
        var module = new TestModule("fixture", "Fixture", "Evidence regression");
        var selected = List.of("first", "second").stream().map(
            method -> new TestCaseDescriptor("fixture." + method, module, TestProfile.CORE, method,
                fixture.getName(), method, List.of())).toList();
        var evidence = new WorkerExecutionEvidence("fixture", selected,
            new WorkerEventWriter(new ObjectMapper(),
                new PrintStream(new ByteArrayOutputStream())));
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
