package com.jdbctest.server.run;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "test_run_module", uniqueConstraints = @UniqueConstraint(name = "uk_test_run_module_run_module", columnNames = {
    "run_id", "module_id"}))
public class TestRunModuleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false)
    private TestRunEntity run;
    @Column(name = "module_id", nullable = false, length = 100)
    private String moduleId;
    @Column(name = "module_name", nullable = false, length = 200)
    private String moduleName;

    protected TestRunModuleEntity() {
    }

    public TestRunModuleEntity(TestRunEntity run, String moduleId, String moduleName) {
        this.run = run;
        this.moduleId = moduleId;
        this.moduleName = moduleName;
    }

    public UUID getId() {
        return id;
    }

    public TestRunEntity getRun() {
        return run;
    }

    public String getModuleId() {
        return moduleId;
    }

    public String getModuleName() {
        return moduleName;
    }
}
