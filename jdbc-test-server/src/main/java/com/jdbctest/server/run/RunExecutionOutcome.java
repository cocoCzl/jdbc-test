package com.jdbctest.server.run;

import tools.jackson.databind.ObjectMapper;

/** Read-only interpretation; original status and report evidence are never rewritten. */
final class RunExecutionOutcome {

    private RunExecutionOutcome() {
    }

    static boolean historicalCaseFailure(TestRunEntity run, ExecutionAccounting accounting) {
        if (run.getStatus() != TestRunStatus.FAILED || !run.isWorkerReportValidated()
            || !"TEST_FAILURE".equals(run.getWorkerTerminationReason())
            || !("TEST_FAILURE".equals(run.getFailureReason())
            || "WORKER_EXECUTION_FAILED".equals(run.getFailureReason()))
            || !Integer.valueOf(2).equals(run.getEvidenceVersion()) || accounting == null
            || accounting.selected() <= 0 || accounting.started() <= 0
            || accounting.selected() != run.getTotalCases()
            || accounting.completed() != accounting.selected()
            || accounting.notRun() != 0 || accounting.incomplete() != 0
            || run.getFailed() + accounting.aborted() <= 0) {
            return false;
        }
        // Protocol 2 TEST_FAILURE alone did not prove cleanup succeeded. Check independent evidence.
        var mapper = new ObjectMapper();
        try {
            var issues = run.getExecutionIssues() == null ? mapper.createArrayNode()
                : mapper.readTree(run.getExecutionIssues());
            var cleanup = run.getCleanupEvidence() == null ? null
                : mapper.readTree(run.getCleanupEvidence());
            return issues != null && issues.isArray() && issues.isEmpty()
                && cleanup != null && "SUCCESS".equals(cleanup.path("status").asString())
                && (run.getWorkerExitCode() == null || run.getWorkerExitCode() == 4);
        } catch (tools.jackson.core.JacksonException exception) {
            return false;
        }
    }

    static String of(TestRunEntity run, ExecutionAccounting accounting) {
        if (run.getStatus() == TestRunStatus.SUCCEEDED || historicalCaseFailure(run, accounting)) {
            return "COMPLETED";
        }
        return switch (run.getStatus()) {
            case QUEUED -> "WAITING";
            case PREPARING, PROBING, RUNNING, GENERATING_REPORT -> "IN_PROGRESS";
            case CANCELLED -> "CANCELLED";
            default -> "ABNORMAL";
        };
    }
}
