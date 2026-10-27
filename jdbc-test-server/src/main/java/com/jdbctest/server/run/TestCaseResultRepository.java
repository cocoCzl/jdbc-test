package com.jdbctest.server.run;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TestCaseResultRepository extends JpaRepository<TestCaseResultEntity, UUID> {

    boolean existsByRun_IdAndScenarioId(UUID runId, String scenarioId);

    @org.springframework.data.jpa.repository.Query("select r.run.id as runId, r.contractConclusion as conclusion, "
        + "r.requirementScope as requirementScope, r.contractInvoked as invoked, count(r) as total "
        + "from TestCaseResultEntity r where r.run.id in :ids "
        + "group by r.run.id, r.contractConclusion, r.requirementScope, r.contractInvoked")
    List<ContractCounts> summarize(@org.springframework.data.repository.query.Param("ids") List<UUID> ids);

    interface ContractCounts {

        UUID getRunId();
        String getConclusion();
        String getRequirementScope();
        boolean getInvoked();
        long getTotal();
    }

    Page<TestCaseResultEntity> findByRun_Id(UUID runId, Pageable pageable);

    Page<TestCaseResultEntity> findByRun_IdAndStatus(UUID runId, TestCaseResultStatus status,
        Pageable pageable);

    Page<TestCaseResultEntity> findByRun_IdAndModuleId(UUID runId, String moduleId,
        Pageable pageable);

    Page<TestCaseResultEntity> findByRun_IdAndStatusAndModuleId(UUID runId,
        TestCaseResultStatus status, String moduleId, Pageable pageable);

    List<TestCaseResultEntity> findByRun_Id(UUID runId);
}
