package com.jdbctest.server.run;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TestRunSelectedScenarioRepository extends JpaRepository<TestRunSelectedScenarioEntity, UUID> {
    List<TestRunSelectedScenarioEntity> findByRun_IdOrderByScenarioId(UUID runId);
}
