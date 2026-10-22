package com.jdbctest.server.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class RunExecutionOutcomeTest {

    private TestRunEntity historical() {
        var run = new TestRunEntity(null, null, "FULL", "connection", "postgresql",
            "postgresql", "jdbc:fixture:never-contacted", "driver", "vendor", "1", "Driver", null);
        run.setStatus(TestRunStatus.FAILED);
        run.setFailureReason("WORKER_EXECUTION_FAILED");
        run.setWorkerTerminationReason("TEST_FAILURE");
        run.markWorkerReportValidated();
        run.freezeExecutionInput("{}");
        run.setTotalCases(1);
        run.recordCase(TestCaseResultStatus.FAILED);
        run.setCleanupEvidence("{\"status\":\"SUCCESS\"}");
        return run;
    }

    private ExecutionAccounting accounting() {
        return new ExecutionAccounting(1, 1, 1, 0, 1, 0, 0, 0, 0, 0, 0, List.of());
    }

    @Test
    void historicalProtocolTwoCaseFailureUsesIndependentEvidenceAndRetainsOriginalState() {
        var run = historical();
        assertEquals("COMPLETED", RunExecutionOutcome.of(run, accounting()));
        assertTrue(RunExecutionOutcome.historicalCaseFailure(run, accounting()));
        assertEquals(TestRunStatus.FAILED, run.getStatus());
        assertEquals("WORKER_EXECUTION_FAILED", run.getFailureReason());
        run.recordWorkerExit(7);
        assertEquals("ABNORMAL", RunExecutionOutcome.of(run, accounting()));
    }

    @Test
    void completeCountsAndTestFailureDoNotHideExecutionReportOrCleanupProblems() {
        var run = historical();
        run.setExecutionIssues("[{\"phase\":\"EXECUTION\",\"message\":\"container failed\"}]");
        assertEquals("ABNORMAL", RunExecutionOutcome.of(run, accounting()));
        run.setExecutionIssues("[]");
        run.setCleanupEvidence("{\"status\":\"FAILED\"}");
        assertEquals("ABNORMAL", RunExecutionOutcome.of(run, accounting()));
        run.setCleanupEvidence("{\"status\":\"SUCCESS\"}");
        run.setFailureReason("REPORT_INCONSISTENT");
        assertEquals("ABNORMAL", RunExecutionOutcome.of(run, accounting()));
        assertFalse(RunExecutionOutcome.historicalCaseFailure(run, null));
    }

    @Test
    void unknownOrInvalidEvidenceIsNeverPromotedToCompleted() {
        var run = historical();
        run.setCleanupEvidence(null);
        assertEquals("ABNORMAL", RunExecutionOutcome.of(run, accounting()));
        run.setCleanupEvidence("malformed");
        assertEquals("ABNORMAL", RunExecutionOutcome.of(run, accounting()));
    }
}
