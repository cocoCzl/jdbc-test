package com.jdbctest.server.run;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "test_run_selected_scenario", uniqueConstraints = @UniqueConstraint(name = "uk_test_run_selected_scenario", columnNames = {"run_id", "scenario_id"}))
public class TestRunSelectedScenarioEntity {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "run_id", nullable = false) private TestRunEntity run;
    @Column(name = "scenario_id", nullable = false, length = 200) private String scenarioId;
    @Column(name = "module_id", nullable = false, length = 100) private String moduleId;
    protected TestRunSelectedScenarioEntity() { }
    TestRunSelectedScenarioEntity(TestRunEntity run, String scenarioId, String moduleId) { this.run = run; this.scenarioId = scenarioId; this.moduleId = moduleId; }
    public String getScenarioId() { return scenarioId; }
    public String getModuleId() { return moduleId; }
}
