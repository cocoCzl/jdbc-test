package com.jdbctest.server.run;

import java.time.Instant;
import java.util.UUID;
public record TestCaseResultResponse(UUID id, String scenarioId, String moduleId, String displayName, TestCaseResultStatus status,
                              Long durationMs, String message, String stackTrace, Instant startedAt, Instant finishedAt, java.util.Map<String, Object> executionEvidence) { }
