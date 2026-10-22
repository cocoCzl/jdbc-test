package com.jdbctest.server.run;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TestRunResponse(UUID id, TestRunStatus status, String profile,
    ConnectionSnapshot connection,
    DriverSnapshot driver, List<String> selectedModules, int totalCases,
    int completedCases,
    int passed, int failed, int skipped, String currentModule,
    String currentTestCase,
    Instant createdAt, Instant startedAt, Instant finishedAt,
    Long durationMs, String failureReason, ExecutionAccounting accounting,
    java.util.Map<String, Object> executionScope,
    java.util.List<java.util.Map<String, Object>> executionIssues,
    java.util.Map<String, Object> cleanupEvidence,
    java.util.Map<String, Object> selectionSnapshot,
    java.util.Map<String, Long> contractSummary,
    com.jdbctest.server.connection.AdapterResponse adapterSnapshot,
    tools.jackson.databind.JsonNode sqlPackage, String workerTerminationReason,
    boolean workerReportValidated, String sqlDialect, String name, String executionOutcome,
    boolean historicalOutcome, String workerArtifactSha256, Integer workerExitCode, tools.jackson.databind.JsonNode workerFinalEvent) {

    public record ConnectionSnapshot(String name, String databaseType, String adapterId,
        String jdbcUrl) {

    }

    public record DriverSnapshot(UUID id, String name, String vendor, String version,
        String driverClass, String packageFingerprint) {

    }
}
