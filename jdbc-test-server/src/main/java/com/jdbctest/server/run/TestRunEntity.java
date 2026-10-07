package com.jdbctest.server.run;

import com.jdbctest.server.connection.DatabaseConnectionProfileEntity;
import com.jdbctest.server.driver.JdbcDriverPackageEntity;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "test_run")
public class TestRunEntity {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "connection_id", nullable = false) private DatabaseConnectionProfileEntity connection;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "driver_id", nullable = false) private JdbcDriverPackageEntity driver;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) private TestRunStatus status;
    @Column(nullable = false, length = 16) private String profile;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "started_at") private Instant startedAt;
    @Column(name = "finished_at") private Instant finishedAt;
    @Column(name = "total_cases", nullable = false) private int totalCases;
    @Column(name = "completed_cases", nullable = false) private int completedCases;
    @Column(nullable = false) private int passed;
    @Column(nullable = false) private int failed;
    @Column(nullable = false) private int skipped;
    @Column(name = "current_module", length = 100) private String currentModule;
    @Column(name = "current_test_case", length = 200) private String currentTestCase;
    @Column(name = "failure_reason", length = 4000) private String failureReason;
    @Column(name = "connection_name_snapshot", nullable = false, length = 200) private String connectionNameSnapshot;
    @Column(name = "database_type_snapshot", nullable = false, length = 80) private String databaseTypeSnapshot;
    @Column(name = "adapter_id_snapshot", nullable = false, length = 100) private String adapterIdSnapshot;
    @Column(name = "jdbc_url_sanitized_snapshot", nullable = false, length = 2000) private String jdbcUrlSanitizedSnapshot;
    @Column(name = "driver_name_snapshot", nullable = false, length = 200) private String driverNameSnapshot;
    @Column(name = "driver_vendor_snapshot", nullable = false, length = 120) private String driverVendorSnapshot;
    @Column(name = "driver_version_snapshot", nullable = false, length = 80) private String driverVersionSnapshot;
    @Column(name = "driver_class_snapshot", nullable = false, length = 300) private String driverClassSnapshot;
    @Column(name = "driver_fingerprint_snapshot", length = 128) private String driverFingerprintSnapshot;

    @Column(name = "execution_issues", columnDefinition = "text") private String executionIssues;
    public String getExecutionIssues() { return executionIssues; }
    void setExecutionIssues(String value) { executionIssues = value; }
    @Column(name = "execution_input", columnDefinition = "text") private String executionInput;
    @Column(name = "evidence_version") private Integer evidenceVersion;
    public String getExecutionInput() { return executionInput; }
    @Column(name = "worker_report_validated", nullable = false) private boolean workerReportValidated;
    public boolean isWorkerReportValidated() { return workerReportValidated; }
    void markWorkerReportValidated() { workerReportValidated = true; }
    public Integer getEvidenceVersion() { return evidenceVersion; }
    void freezeExecutionInput(String value) { executionInput = value; evidenceVersion = 2; }

    protected TestRunEntity() { }
    public TestRunEntity(DatabaseConnectionProfileEntity connection, JdbcDriverPackageEntity driver, String profile,
                         String connectionNameSnapshot, String databaseTypeSnapshot, String adapterIdSnapshot, String jdbcUrlSanitizedSnapshot,
                         String driverNameSnapshot, String driverVendorSnapshot, String driverVersionSnapshot,
                         String driverClassSnapshot, String driverFingerprintSnapshot) {
        this.connection = connection; this.driver = driver; this.profile = profile; this.status = TestRunStatus.QUEUED;
        this.connectionNameSnapshot = connectionNameSnapshot; this.databaseTypeSnapshot = databaseTypeSnapshot; this.adapterIdSnapshot = adapterIdSnapshot;
        this.jdbcUrlSanitizedSnapshot = jdbcUrlSanitizedSnapshot; this.driverNameSnapshot = driverNameSnapshot;
        this.driverVendorSnapshot = driverVendorSnapshot; this.driverVersionSnapshot = driverVersionSnapshot;
        this.driverClassSnapshot = driverClassSnapshot; this.driverFingerprintSnapshot = driverFingerprintSnapshot;
    }
    /** Compatibility constructor retained for Phase 6 persistence tests. */
    public TestRunEntity(DatabaseConnectionProfileEntity connection, JdbcDriverPackageEntity driver, String profile,
                         String connectionNameSnapshot, String databaseTypeSnapshot, String jdbcUrlSanitizedSnapshot,
                         String driverNameSnapshot, String driverVendorSnapshot, String driverVersionSnapshot,
                         String driverClassSnapshot, String driverFingerprintSnapshot) {
        this(connection, driver, profile, connectionNameSnapshot, databaseTypeSnapshot, connection.getAdapterId(), jdbcUrlSanitizedSnapshot,
                driverNameSnapshot, driverVendorSnapshot, driverVersionSnapshot, driverClassSnapshot, driverFingerprintSnapshot);
    }
    @PrePersist void onCreate() { createdAt = Instant.now(); }
    public UUID getId() { return id; }
    public DatabaseConnectionProfileEntity getConnection() { return connection; }
    public JdbcDriverPackageEntity getDriver() { return driver; }
    public TestRunStatus getStatus() { return status; }
    void setStatus(TestRunStatus value) { status = value; }
    public String getProfile() { return profile; }
    public Instant getCreatedAt() { return createdAt; }
    public String getConnectionNameSnapshot() { return connectionNameSnapshot; }
    public String getDatabaseTypeSnapshot() { return databaseTypeSnapshot; }
    public String getAdapterIdSnapshot() { return adapterIdSnapshot; }
    public String getJdbcUrlSanitizedSnapshot() { return jdbcUrlSanitizedSnapshot; }
    public String getDriverClassSnapshot() { return driverClassSnapshot; }
    public String getDriverNameSnapshot() { return driverNameSnapshot; }
    public String getDriverVendorSnapshot() { return driverVendorSnapshot; }
    public String getDriverVersionSnapshot() { return driverVersionSnapshot; }
    public String getDriverFingerprintSnapshot() { return driverFingerprintSnapshot; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public int getTotalCases() { return totalCases; }
    public int getCompletedCases() { return completedCases; }
    public int getPassed() { return passed; }
    public int getFailed() { return failed; }
    public int getSkipped() { return skipped; }
    public String getCurrentModule() { return currentModule; }
    public String getCurrentTestCase() { return currentTestCase; }
    public String getFailureReason() { return failureReason; }
    void markStarted() { startedAt = Instant.now(); }
    void markFinished() { finishedAt = Instant.now(); currentModule = null; currentTestCase = null; }
    void setCurrentModule(String value) { currentModule = value; }
    void setCurrentTestCase(String value) { currentTestCase = value; }
    void recordCase(TestCaseResultStatus result) { completedCases++; if (result == TestCaseResultStatus.PASSED) passed++; else if (result == TestCaseResultStatus.FAILED) failed++; else if (result == TestCaseResultStatus.SKIPPED) skipped++; }
    void setTotalCases(int value) { totalCases = value; }
    void setFailureReason(String value) { failureReason = value; }
}
