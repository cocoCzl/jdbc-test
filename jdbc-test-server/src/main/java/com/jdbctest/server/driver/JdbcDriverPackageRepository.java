package com.jdbctest.server.driver;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JdbcDriverPackageRepository extends JpaRepository<JdbcDriverPackageEntity, UUID> {

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select driver from JdbcDriverPackageEntity driver where driver.id = :id")
    Optional<JdbcDriverPackageEntity> findForUpdate(@org.springframework.data.repository.query.Param("id") UUID id);

    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query("update JdbcDriverPackageEntity driver set driver.status = :status, "
        + "driver.updatedAt = :updatedAt where driver.id = :id and driver.validationRevision = :revision")
    int applyValidation(@org.springframework.data.repository.query.Param("id") UUID id,
        @org.springframework.data.repository.query.Param("revision") long revision,
        @org.springframework.data.repository.query.Param("status") DriverPackageStatus status,
        @org.springframework.data.repository.query.Param("updatedAt") java.time.Instant updatedAt);

    Optional<JdbcDriverPackageEntity> findByPackageFingerprint(String packageFingerprint);
}
