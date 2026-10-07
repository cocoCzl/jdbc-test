package com.jdbctest.server.run;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.List;
import java.util.UUID;

public interface TestRunRepository extends JpaRepository<TestRunEntity, UUID> {
    @org.springframework.data.jpa.repository.Query("select run from TestRunEntity run where run.status = :status")
    Page<TestRunEntity> findByStatus(@org.springframework.data.repository.query.Param("status") TestRunStatus status, Pageable pageable);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select run from TestRunEntity run where run.id = :id")
    java.util.Optional<TestRunEntity> findForUpdate(@org.springframework.data.repository.query.Param("id") UUID id);
    long countByDriver_Id(UUID driverId);
    long countByConnection_Id(UUID connectionId);
    List<TestRunEntity> findByStatusIn(List<TestRunStatus> statuses);
    @org.springframework.data.jpa.repository.Query("select run.id from TestRunEntity run where run.status in :statuses")
    List<UUID> findIdsByStatusIn(@org.springframework.data.repository.query.Param("statuses") List<TestRunStatus> statuses);
}
