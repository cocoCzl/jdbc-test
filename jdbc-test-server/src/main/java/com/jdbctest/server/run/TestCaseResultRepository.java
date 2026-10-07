package com.jdbctest.server.run;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface TestCaseResultRepository extends JpaRepository<TestCaseResultEntity, UUID> {
    Page<TestCaseResultEntity> findByRun_Id(UUID runId, Pageable pageable);
    Page<TestCaseResultEntity> findByRun_IdAndStatus(UUID runId, TestCaseResultStatus status, Pageable pageable);
    Page<TestCaseResultEntity> findByRun_IdAndModuleId(UUID runId, String moduleId, Pageable pageable);
    List<TestCaseResultEntity> findByRun_Id(UUID runId);
}
