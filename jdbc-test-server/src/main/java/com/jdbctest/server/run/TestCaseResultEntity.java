package com.jdbctest.server.run;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "test_case_result", uniqueConstraints = @UniqueConstraint(name = "uk_test_case_result_run_scenario", columnNames = {"run_id", "scenario_id"}))
public class TestCaseResultEntity {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "run_id", nullable = false) private TestRunEntity run;
    @Column(name = "scenario_id", nullable = false, length = 200) private String scenarioId;
    @Column(name = "module_id", nullable = false, length = 100) private String moduleId;
    @Column(name = "display_name", nullable = false, length = 500) private String displayName;
    @Column(name = "test_class", length = 300) private String testClass;
    @Column(name = "test_method", length = 300) private String testMethod;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private TestCaseResultStatus status;
    @Column(name = "duration_ms") private Long durationMs;
    @Column(length = 4000) private String message;
    @Column(name = "stack_trace", length = 12000) private String stackTrace;
    @Column(name = "started_at") private Instant startedAt;
    @Column(name = "finished_at") private Instant finishedAt;
    protected TestCaseResultEntity() { }
    public TestCaseResultEntity(TestRunEntity run, String scenarioId, String moduleId, String displayName, TestCaseResultStatus status) {
        this.run = run; this.scenarioId = scenarioId; this.moduleId = moduleId; this.displayName = displayName; this.status = status;
    }
    public UUID getId() { return id; }
    public TestRunEntity getRun() { return run; }
    public String getScenarioId() { return scenarioId; }
    public String getModuleId() { return moduleId; }
    public String getDisplayName() { return displayName; }
    public TestCaseResultStatus getStatus() { return status; }
    public Long getDurationMs() { return durationMs; }
    public String getMessage() { return message; }
    public String getStackTrace() { return stackTrace; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    void complete(Long duration, String message, String stackTrace) { this.durationMs = duration; this.message = message; this.stackTrace = stackTrace; this.finishedAt = Instant.now(); }
}
