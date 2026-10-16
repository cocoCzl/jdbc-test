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
    java.util.Map<String, Object> cleanupEvidence) {

    public record ConnectionSnapshot(String name, String databaseType, String adapterId,
        String jdbcUrl) {

    }

    public record DriverSnapshot(UUID id, String name, String vendor, String version,
        String driverClass, String packageFingerprint) {

    }
}
