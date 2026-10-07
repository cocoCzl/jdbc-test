package com.jdbctest.server.driver;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;
import java.util.Optional;

public interface JdbcDriverPackageRepository extends JpaRepository<JdbcDriverPackageEntity, UUID> {
    Optional<JdbcDriverPackageEntity> findByPackageFingerprint(String packageFingerprint);
}
