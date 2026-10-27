package com.jdbctest.server.run;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TestRunSelectedScenarioRepository extends
    JpaRepository<TestRunSelectedScenarioEntity, UUID> {

    java.util.Optional<TestRunSelectedScenarioEntity> findByRun_IdAndScenarioIdAndModuleId(
        UUID runId, String scenarioId, String moduleId);

    @org.springframework.data.jpa.repository.Query("select s.run.id as runId, s.moduleId as moduleId, count(s) as total, "
        + "sum(case when s.executionStartedAt is not null then 1 else 0 end) as started, "
        + "sum(case when s.resultStatus is null and s.executionStartedAt is null then 1 else 0 end) as pending, "
        + "sum(case when s.resultStatus is null and s.executionStartedAt is not null then 1 else 0 end) as running "
        + "from TestRunSelectedScenarioEntity s where s.run.id in :ids group by s.run.id, s.moduleId")
    List<SelectionCounts> summarize(@org.springframework.data.repository.query.Param("ids") List<UUID> ids);

    interface SelectionCounts {

        UUID getRunId();
        String getModuleId();
        long getTotal();
        long getStarted();
        long getPending();
        long getRunning();
    }

    List<TestRunSelectedScenarioEntity> findByRun_IdOrderByScenarioId(UUID runId);
}
