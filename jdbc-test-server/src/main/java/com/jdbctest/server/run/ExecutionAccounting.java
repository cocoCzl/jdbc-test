package com.jdbctest.server.run;

import java.time.Instant;
import java.util.List;

public record ExecutionAccounting(int selected, int started, int completed, int passed, int failed,
    int skipped, int aborted, int pending, int running, int notRun,
    int incomplete,
    List<Scenario> scenarios) {

    public record Scenario(String scenarioId, String moduleId, String state, Instant startedAt,
        Instant finishedAt, String notExecutedReason) {

    }

    static ExecutionAccounting of(TestRunEntity run,
        List<TestRunSelectedScenarioEntity> selection) {
        if (!Integer.valueOf(2).equals(run.getEvidenceVersion())) {
            return null;
        }
        boolean terminal = TestRunStateTransitions.terminal(run.getStatus());
        List<Scenario> rows = selection.stream()
            .map(item -> new Scenario(item.getScenarioId(), item.getModuleId(),
                item.getResultStatus() != null ? item.getResultStatus()
                    : item.getExecutionStartedAt() != null
                        ? (terminal ? "INCOMPLETE" : "RUNNING")
                        : (terminal ? "NOT_RUN" : "PENDING"),
                item.getExecutionStartedAt(), item.getExecutionFinishedAt(),
                item.getResultStatus() == null && terminal ?
                    run.getFailureReason() == null ? "CONTAINER_OR_EXECUTION_FAILURE" : run.getFailureReason()
                    : null)).toList();
        int started = (int) rows.stream().filter(row -> row.startedAt() != null).count();
        return new ExecutionAccounting(rows.size(), started, run.getCompletedCases(),
            run.getPassed(),
            run.getFailed(), run.getSkipped(),
            run.getCompletedCases() - run.getPassed() - run.getFailed() - run.getSkipped(),
            count(rows, "PENDING"), count(rows, "RUNNING"), count(rows, "NOT_RUN"),
            count(rows, "INCOMPLETE"), rows);
    }

    private static int count(List<Scenario> rows, String state) {
        return (int) rows.stream().filter(row -> row.state().equals(state)).count();
    }
}
