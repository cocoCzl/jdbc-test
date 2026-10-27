package com.jdbctest.server.run;

import java.time.Instant;
import java.util.UUID;

/** Shared read contract for full history and the closed list projection. */
public interface TestRunView {

    long getEventSequence();

    String getWorkerArtifactSha256();

    String getName();

    Integer getWorkerExitCode();

    String getWorkerFinalEvent();

    String getCleanupEvidence();

    String getWorkerTerminationReason();

    String getExecutionIssues();

    boolean isWorkerReportValidated();

    Integer getEvidenceVersion();

    UUID getId();

    TestRunStatus getStatus();

    String getProfile();

    Instant getCreatedAt();

    String getConnectionNameSnapshot();

    String getDatabaseTypeSnapshot();

    String getAdapterIdSnapshot();

    String getJdbcUrlSanitizedSnapshot();

    String getDriverClassSnapshot();

    String getDriverNameSnapshot();

    String getDriverVendorSnapshot();

    String getDriverVersionSnapshot();

    String getDriverFingerprintSnapshot();

    Instant getStartedAt();

    Instant getFinishedAt();

    int getTotalCases();

    int getCompletedCases();

    int getPassed();

    int getFailed();

    int getSkipped();

    String getCurrentModule();

    String getCurrentTestCase();

    String getFailureReason();

    UUID getDriverId();
}
