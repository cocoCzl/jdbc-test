package com.jdbctest.server.run;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TestCaseResultRepository extends JpaRepository<TestCaseResultEntity, UUID> {

    Page<TestCaseResultEntity> findByRun_Id(UUID runId, Pageable pageable);

    Page<TestCaseResultEntity> findByRun_IdAndStatus(UUID runId, TestCaseResultStatus status,
        Pageable pageable);

    Page<TestCaseResultEntity> findByRun_IdAndModuleId(UUID runId, String moduleId,
        Pageable pageable);

    Page<TestCaseResultEntity> findByRun_IdAndStatusAndModuleId(UUID runId,
        TestCaseResultStatus status, String moduleId, Pageable pageable);

    List<TestCaseResultEntity> findByRun_Id(UUID runId);
}
